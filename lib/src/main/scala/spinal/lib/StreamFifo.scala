package spinal.lib

import spinal.core._
import spinal.lib.eda.bench.{AlteraStdTargets, Bench, Rtl, XilinxStdTargets}

trait StreamFifoInterface[T <: Data] {
  def push: Stream[T]
  def pop: Stream[T]
  def pushOccupancy: UInt
  def popOccupancy: UInt
}

object StreamFifo {
  def apply[T <: Data](dataType: T, depth: Int) = new StreamFifo(dataType, depth)
}

class StreamFifo[T <: Data](dataType: HardType[T], depth: Int) extends Component {
  require(depth >= 0)
  val io = new Bundle {
    val push         = slave Stream (dataType)
    val pop          = master Stream (dataType)
    val flush        = in Bool () default (False)
    val occupancy    = out UInt (log2Up(depth + 1) bits)
    val availability = out UInt (log2Up(depth + 1) bits)
  }

  val bypass = (depth == 0) generate new Area {
    io.push >> io.pop
    io.occupancy    := 0
    io.availability := 0
  }
  val oneStage = (depth == 1) generate new Area {
    io.push.m2sPipe(flush = io.flush) >> io.pop
    io.occupancy    := U(io.pop.valid)
    io.availability := U(!io.pop.valid)
  }
  val logic = (depth > 1) generate new Area {
    val ram             = Mem(dataType, depth)
    val pushPtr         = Counter(depth)
    val popPtr          = Counter(depth)
    val ptrMatch        = pushPtr === popPtr
    val risingOccupancy = RegInit(False)
    val pushing         = io.push.fire
    val popping         = io.pop.fire
    val empty           = ptrMatch & !risingOccupancy
    val full            = ptrMatch & risingOccupancy

    io.push.ready  := !full
    io.pop.valid   := !empty & !(RegNext(popPtr.valueNext === pushPtr, False) & !full) // mem write to read propagation
    io.pop.payload := ram.readSync(popPtr.valueNext)

    when(pushing =/= popping) {
      risingOccupancy := pushing
    }
    when(pushing) {
      ram(pushPtr.value) := io.push.payload
      pushPtr.increment()
    }
    when(popping) {
      popPtr.increment()
    }

    val ptrDif = pushPtr - popPtr
    if (isPow2(depth)) {
      io.occupancy    := ((risingOccupancy && ptrMatch) ## ptrDif).asUInt
      io.availability := ((!risingOccupancy && ptrMatch) ## (popPtr - pushPtr)).asUInt
    } else {
      when(ptrMatch) {
        io.occupancy    := Mux(risingOccupancy, U(depth), U(0))
        io.availability := Mux(risingOccupancy, U(0), U(depth))
      } otherwise {
        io.occupancy    := Mux(pushPtr > popPtr, ptrDif, U(depth) + ptrDif)
        io.availability := Mux(pushPtr > popPtr, U(depth) + (popPtr - pushPtr), (popPtr - pushPtr))
      }
    }

    when(io.flush) {
      pushPtr.clear()
      popPtr.clear()
      risingOccupancy := False
    }
  }
}

object StreamFifoLowLatency {
  def apply[T <: Data](dataType: T, depth: Int) = new StreamFifoLowLatency(dataType, depth)
}

class StreamFifoLowLatency[T <: Data](
    val dataType: HardType[T],
    val depth: Int,
    val latency: Int = 0,
    useVec: Boolean = false
) extends Component {
  require(depth >= 1)
  val io = new Bundle with StreamFifoInterface[T] {
    val push                         = slave Stream (dataType)
    val pop                          = master Stream (dataType)
    val flush                        = in Bool () default (False)
    val occupancy                    = out UInt (log2Up(depth + 1) bit)
    override def pushOccupancy: UInt = occupancy
    override def popOccupancy: UInt  = occupancy
  }
  val vec             = useVec generate Vec(Reg(dataType), depth)
  val ram             = !useVec generate Mem(dataType, depth)
  val pushPtr         = Counter(depth)
  val popPtr          = Counter(depth)
  val ptrMatch        = pushPtr === popPtr
  val risingOccupancy = RegInit(False)
  val empty           = ptrMatch & !risingOccupancy
  val full            = ptrMatch & risingOccupancy

  val pushing = io.push.fire
  val popping = io.pop.fire

  io.push.ready := !full

  val readed = if (useVec) vec(popPtr.value) else ram(popPtr.value)

  latency match {
    case 0 => {
      when(!empty) {
        io.pop.valid   := True
        io.pop.payload := readed
      } otherwise {
        io.pop.valid   := io.push.valid
        io.pop.payload := io.push.payload
      }
    }
    case 1 => {
      io.pop.valid   := !empty
      io.pop.payload := readed
    }
  }
  when(pushing =/= popping) {
    risingOccupancy := pushing
  }
  when(pushing) {
    if (useVec)
      vec.write(pushPtr.value, io.push.payload)
    else
      ram.write(pushPtr.value, io.push.payload)
    pushPtr.increment()
  }
  when(popping) {
    popPtr.increment()
  }

  val ptrDif = pushPtr - popPtr
  if (isPow2(depth))
    io.occupancy := ((risingOccupancy && ptrMatch) ## ptrDif).asUInt
  else {
    when(ptrMatch) {
      io.occupancy := Mux(risingOccupancy, U(depth), U(0))
    } otherwise {
      io.occupancy := Mux(pushPtr > popPtr, ptrDif, U(depth) + ptrDif)
    }
  }

  when(io.flush) {
    pushPtr.clear()
    popPtr.clear()
    risingOccupancy := False
  }
}

object StreamFifoCC {
  def apply[T <: Data](dataType: HardType[T], depth: Int, pushClock: ClockDomain, popClock: ClockDomain) =
    new StreamFifoCC(dataType, depth, pushClock, popClock)
}

//class   StreamFifoCC[T <: Data](dataType: HardType[T], val depth: Int, val pushClock: ClockDomain,val popClock: ClockDomain) extends Component {
//
//  assert(isPow2(depth) & depth >= 2, "The depth of the StreamFifoCC must be a power of 2 and equal or bigger than 2")
//
//  val io = new Bundle with StreamFifoInterface[T]{
//    val push          = slave  Stream(dataType)
//    val pop           = master Stream(dataType)
//    val pushOccupancy = out UInt(log2Up(depth + 1) bits)
//    val popOccupancy  = out UInt(log2Up(depth + 1) bits)
//  }
//
//  val ptrWidth = log2Up(depth) + 1
//  def isFull(a: Bits, b: Bits) = a(ptrWidth - 1 downto ptrWidth - 2) === ~b(ptrWidth - 1 downto ptrWidth - 2) && a(ptrWidth - 3 downto 0) === b(ptrWidth - 3 downto 0)
//  def isEmpty(a: Bits, b: Bits) = a === b
//
//  val ram = Mem(dataType, depth)
//
//  val popToPushGray = Bits(ptrWidth bits)
//  val pushToPopGray = Bits(ptrWidth bits)
//
//  val pushCC = new ClockingArea(pushClock) {
//    val pushPtr     = Counter(depth << 1)
//    val pushPtrGray = RegNext(toGray(pushPtr.valueNext)) init(0)
//    val popPtrGray  = BufferCC(popToPushGray, B(0, ptrWidth bits))
//    val full        = isFull(pushPtrGray, popPtrGray)
//
//    io.push.ready := !full
//
//    when(io.push.fire) {
//      ram(pushPtr.resized) := io.push.payload
//      pushPtr.increment()
//    }
//
//    io.pushOccupancy := (pushPtr - fromGray(popPtrGray)).resized
//  }
//
//  val popCC = new ClockingArea(popClock) {
//    val popPtr      = Counter(depth << 1)
//    val popPtrGray  = RegNext(toGray(popPtr.valueNext)) init(0)
//    val pushPtrGray = BufferCC(pushToPopGray, B(0, ptrWidth bit))
//    val empty       = isEmpty(popPtrGray, pushPtrGray)
//
//    io.pop.valid   := !empty
//    io.pop.payload := ram.readSync(popPtr.valueNext.resized, clockCrossing = true)
//
//    when(io.pop.fire) {
//      popPtr.increment()
//    }
//
//    io.popOccupancy := (fromGray(pushPtrGray) - popPtr).resized
//  }
//
//  pushToPopGray := pushCC.pushPtrGray
//  popToPushGray := popCC.popPtrGray
//}

class StreamFifoCC[T <: Data](
    val dataType: HardType[T],
    val depth: Int,
    val pushClock: ClockDomain,
    val popClock: ClockDomain,
    val withPopBufferedReset: Boolean = true
) extends Component {

  assert(isPow2(depth) & depth >= 2, "The depth of the StreamFifoCC must be a power of 2 and equal or bigger than 2")

  val io = new Bundle with StreamFifoInterface[T] {
    val push          = slave Stream (dataType)
    val pop           = master Stream (dataType)
    val pushOccupancy = out UInt (log2Up(depth + 1) bits)
    val popOccupancy  = out UInt (log2Up(depth + 1) bits)
  }

  val ptrWidth = log2Up(depth) + 1
  def isFull(a: Bits, b: Bits) = a(ptrWidth - 1 downto ptrWidth - 2) === ~b(ptrWidth - 1 downto ptrWidth - 2) && a(
    ptrWidth - 3 downto 0
  ) === b(ptrWidth - 3 downto 0)
  def isEmpty(a: Bits, b: Bits) = a === b

  val ram = Mem(dataType, depth)

  val popToPushGray = Bits(ptrWidth bits)
  val pushToPopGray = Bits(ptrWidth bits)

  val pushCC = new ClockingArea(pushClock) {
    val pushPtr     = Reg(UInt(log2Up(2 * depth) bits)) init (0)
    val pushPtrPlus = pushPtr + 1
    val pushPtrGray = RegNextWhen(toGray(pushPtrPlus), io.push.fire) init (0)
    val popPtrGray  = BufferCC(popToPushGray, B(0, ptrWidth bits))
    val full        = isFull(pushPtrGray, popPtrGray)

    io.push.ready := !full

    when(io.push.fire) {
      ram(pushPtr.resized) := io.push.payload
      pushPtr              := pushPtrPlus
    }

    io.pushOccupancy := (pushPtr - fromGray(popPtrGray)).resized
  }

  val finalPopCd = popClock.withOptionalBufferedResetFrom(withPopBufferedReset)(pushClock)
  val popCC = new ClockingArea(finalPopCd) {
    val popPtr      = Reg(UInt(log2Up(2 * depth) bits)) init (0)
    val popPtrPlus  = popPtr + 1
    val popPtrGray  = RegNextWhen(toGray(popPtrPlus), io.pop.fire) init (0)
    val pushPtrGray = BufferCC(pushToPopGray, B(0, ptrWidth bit))
    val empty       = isEmpty(popPtrGray, pushPtrGray)

    io.pop.valid   := !empty
    io.pop.payload := ram.readSync((io.pop.fire ? popPtrPlus | popPtr).resized, clockCrossing = true)

    when(io.pop.fire) {
      popPtr := popPtrPlus
    }

    io.popOccupancy := (fromGray(pushPtrGray) - popPtr).resized
  }

  pushToPopGray := pushCC.pushPtrGray
  popToPushGray := popCC.popPtrGray
}

case class StreamFifoMultiChannelPush[T <: Data](payloadType: HardType[T], channelCount: Int)
    extends Bundle
    with IMasterSlave {
  val channel = Bits(channelCount bits)
  val full    = Bool()
  val stream  = Stream(payloadType)

  override def asMaster(): Unit = {
    out(channel)
    master(stream)
    in(full)
  }
}

case class StreamFifoMultiChannelPop[T <: Data](payloadType: HardType[T], channelCount: Int)
    extends Bundle
    with IMasterSlave {
  val channel = Bits(channelCount bits)
  val empty   = Bits(channelCount bits)
  val stream  = Stream(payloadType)

  override def asMaster(): Unit = {
    out(channel)
    slave(stream)
    in(empty)
  }

  def toStreams(withCombinatorialBuffer: Boolean) = new Area {
    val bufferIn, bufferOut = Vec(Stream(payloadType), channelCount)
    (bufferOut, bufferIn).zipped.foreach((s, m) => if (withCombinatorialBuffer) s </< m else s <-< m)

    val needRefill = B(bufferIn.map(_.ready))
    val selOh      = OHMasking.first(needRefill & ~empty) // TODO
    val nonEmpty   = (~empty).orR
    channel := selOh
    for ((feed, sel) <- (bufferIn, selOh.asBools).zipped) {
      feed.valid   := sel && nonEmpty
      feed.payload := stream.payload
    }
    stream.ready := (selOh & B(bufferIn.map(_.ready))).orR
  }.setCompositeName(this, "toStreams", true).bufferOut

}

//Emulate multiple fifo but with one push,one pop port and a shared storage
//io.availability has one cycle latency
case class StreamFifoMultiChannelSharedSpace[T <: Data](
    payloadType: HardType[T],
    channelCount: Int,
    depth: Int,
    withAllocationFifo: Boolean = false
) extends Component {
  assert(isPow2(depth))
  val io = new Bundle {
    val push         = slave(StreamFifoMultiChannelPush(payloadType, channelCount))
    val pop          = slave(StreamFifoMultiChannelPop(payloadType, channelCount))
    val availability = out UInt (log2Up(depth) + 1 bits)
  }
  val ptrWidth = log2Up(depth)

  val payloadRam = Mem(payloadType(), depth)
  val nextRam    = Mem(UInt(ptrWidth bits), depth)

  val full = False
  io.push.full         := full
  io.push.stream.ready := !full

  val pushNextEntry = UInt(ptrWidth bits)
  val popNextEntry  = nextRam.wordType()

  val channels = for (channelId <- 0 until channelCount) yield new Area {
    val valid    = RegInit(False)
    val headPtr  = Reg(UInt(ptrWidth bits))
    val lastPtr  = Reg(UInt(ptrWidth bits))
    val lastFire = False
    when(io.pop.stream.fire && io.pop.channel(channelId)) {
      headPtr := popNextEntry
      when(headPtr === lastPtr) {
        lastFire := True
        valid    := False
      }
    }

    when(!valid || lastFire) {
      headPtr := pushNextEntry
    }

    when(io.push.stream.fire && io.push.channel(channelId)) {
      lastPtr := pushNextEntry
      valid   := True
    }
    io.pop.empty(channelId) := !valid
  }

  val pushLogic = new Area {
    val previousAddress = MuxOH(io.push.channel, channels.map(_.lastPtr))
    when(io.push.stream.fire) {
      payloadRam.write(pushNextEntry, io.push.stream.payload)
      when((channels.map(_.valid).asBits() & io.push.channel).orR) {
        nextRam.write(previousAddress, pushNextEntry)
      }
    }
  }

  val popLogic = new Area {
    val readAddress = channels.map(_.headPtr).read(OHToUInt(io.pop.channel))
    io.pop.stream.valid   := (io.pop.channel & ~io.pop.empty).orR
    io.pop.stream.payload := payloadRam.readAsync(readAddress)
    popNextEntry          := nextRam.readAsync(readAddress)
  }

  val allocationByCounter = !withAllocationFifo generate new Area {
    val allocationPtr = Reg(UInt(ptrWidth bits)) init (0)

    when(io.push.stream.fire) {
      allocationPtr := allocationPtr + 1
    }

    val onChannels = for (c <- channels) yield new Area {
      full setWhen (c.valid && allocationPtr === c.headPtr)
      val wasValid     = RegNext(c.valid) init (False)
      val availability = RegNext(c.headPtr - allocationPtr)
    }

    val (availabilityValid, availabilityValue) = onChannels.map(c => (c.wasValid, c.availability)).reduceBalancedTree {
      case (a, b) => (a._1 || b._1, (a._1 && (!b._1 || a._2 < b._2)) ? a._2 | b._2)
    }
    io.availability := (availabilityValid ? availabilityValue | depth)

    pushNextEntry := allocationPtr
  }

  val allocationByFifo = withAllocationFifo generate new Area {
    ???
  }

}

object StreamFifoMultiChannelBench extends App {
  val payloadType = HardType(Bits(8 bits))
  class BenchFpga(channelCount: Int) extends Rtl {
    override def getName(): String    = "Bench" + channelCount
    override def getRtlPath(): String = getName() + ".v"
    SpinalVerilog(new Component {
      val push = slave(StreamFifoMultiChannelPush(payloadType, channelCount))
      val pop  = slave(StreamFifoMultiChannelPop(payloadType, channelCount))
      val fifo = StreamFifoMultiChannelSharedSpace(payloadType, channelCount, 32)

      fifo.io.push.channel := RegNext(push.channel)
      push.full            := RegNext(fifo.io.push.full)
      fifo.io.push.stream <-/< push.stream

      fifo.io.pop.channel := RegNext(pop.channel)
      pop.empty           := RegNext(fifo.io.pop.empty)
      pop.stream <-/< fifo.io.pop.stream

      setDefinitionName(BenchFpga.this.getName())
    })
  }
  class BenchFpga2(channelCount: Int) extends Rtl {
    override def getName(): String    = "BenchToStream" + channelCount
    override def getRtlPath(): String = getName() + ".v"
    SpinalVerilog(new Component {
      val push = slave(StreamFifoMultiChannelPush(payloadType, channelCount))
      val fifo = StreamFifoMultiChannelSharedSpace(payloadType, channelCount, 32)

      fifo.io.push.channel := RegNext(push.channel)
      push.full            := RegNext(fifo.io.push.full)
      fifo.io.push.stream <-/< push.stream

      setDefinitionName(BenchFpga2.this.getName())

      val outputs = fifo.io.pop.toStreams(false).map(_.s2mPipe().asMaster())
    })
  }

  val rtls = List(2, 4, 8).map(width => new BenchFpga(width)) ++ List(2, 4, 8).map(width => new BenchFpga2(width))

  val targets = XilinxStdTargets() // ++ AlteraStdTargets()

  Bench(rtls, targets)
}
