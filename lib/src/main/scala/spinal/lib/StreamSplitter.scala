package spinal.lib

import spinal.core._
import scala.collection.Seq

//TODOTEST
/**  Demultiplex one stream into multiple output streams, always selecting only one at a time.
  */
object StreamDemux {
  def apply[T <: Data](input: Stream[T], select: UInt, portCount: Int): Vec[Stream[T]] = {
    val c = new StreamDemux(input.payload, portCount)
    c.io.input << input
    c.io.select := select
    c.io.outputs
  }
}

class StreamDemux[T <: Data](dataType: T, portCount: Int) extends Component {
  val io = new Bundle {
    val select = in UInt (log2Up(portCount) bit)
    val input = slave Stream (dataType)
    val outputs = Vec(master Stream (dataType), portCount)
  }
  io.input.ready := False
  for (i <- 0 to portCount - 1) {
    io.outputs(i).payload := io.input.payload
    when(i =/= io.select) {
      io.outputs(i).valid := False
    } otherwise {
      io.outputs(i).valid := io.input.valid
      io.input.ready := io.outputs(i).ready
    }
  }
}

object StreamArbiter {

  /** An Arbitration will choose which input stream to take at any moment. */
  object Arbitration {
    def lowerFirst(core: StreamArbiter[_ <: Data]) = new Area {
      import core._
      maskProposal := OHMasking.first(Vec(io.inputs.map(_.valid)))
    }

    /** This arbiter contains an implicit transactionLock */
    def sequentialOrder(core: StreamArbiter[_]) = new Area {
      import core._
      val counter = Counter(core.portCount, io.output.fire)
      for (i <- 0 to core.portCount - 1) {
        maskProposal(i) := False
      }
      maskProposal(counter) := True
    }

    def roundRobin(core: StreamArbiter[_ <: Data]) = new Area {
      import core._
      for (bitId <- maskLocked.range) {
        maskLocked(bitId) init (Bool(bitId == maskLocked.length - 1))
      }
      // maskProposal := maskLocked
      maskProposal := OHMasking.roundRobin(
        Vec(io.inputs.map(_.valid)),
        Vec(maskLocked.last +: maskLocked.take(maskLocked.length - 1))
      )
    }
  }

  /** When a lock activates, the currently chosen input won't change until it is released. */
  object Lock {
    def none(core: StreamArbiter[_]) = new Area {}

    /** Many handshaking protocols require that once valid is set, it must stay asserted and the payload
      *  must not changed until the transaction fires, e.g. until ready is set as well. Since some arbitrations
      *  may change their chosen input at any moment in time (which is not wrong), this may violate such
      *  handshake protocols. Use this lock to be compliant in those cases.
      */
    def transactionLock(core: StreamArbiter[_]) = new Area {
      import core._
      locked setWhen (io.output.valid)
      locked.clearWhen(io.output.fire)
    }

    /** This lock ensures that once a fragmented transaction is started, it will be finished without
      * interruptions from other streams. Without this, fragments of different streams will get intermingled.
      * This is only relevant for fragmented streams.
      */
    def fragmentLock(core: StreamArbiter[_]) = new Area {
      val realCore = core.asInstanceOf[StreamArbiter[Fragment[_]]]
      import realCore._
      locked setWhen (io.output.valid)
      locked.clearWhen(io.output.fire && io.output.last)
    }
  }
}

/**  A StreamArbiter is like a StreamMux, but with built-in complex selection logic that can arbitrate input
  *  streams based on a schedule or handle fragmented streams. Use a StreamArbiterFactory to create instances of this class.
  */
class StreamArbiter[T <: Data](dataType: HardType[T], val portCount: Int)(
    val arbitrationFactory: (StreamArbiter[T]) => Area,
    val lockFactory: (StreamArbiter[T]) => Area
) extends Component {
  val io = new Bundle {
    val inputs = Vec(slave Stream (dataType), portCount)
    val output = master Stream (dataType)
    val chosen = out UInt (log2Up(portCount) bit)
    val chosenOH = out Bits (portCount bit)
  }

  val locked = RegInit(False).allowUnsetRegToAvoidLatch

  val maskProposal = Vec(Bool(), portCount)
  val maskLocked = Reg(Vec(Bool(), portCount))
  val maskRouted = Mux(locked, maskLocked, maskProposal)

  when(io.output.valid) {
    maskLocked := maskRouted
  }

  val arbitration = arbitrationFactory(this)
  val lock = lockFactory(this)

  io.output.valid := (io.inputs, maskRouted).zipped.map(_.valid & _).reduce(_ | _)
  io.output.payload := MuxOH(maskRouted, Vec(io.inputs.map(_.payload)))
  (io.inputs, maskRouted).zipped.foreach(_.ready := _ & io.output.ready)

  io.chosenOH := maskRouted.asBits
  io.chosen := OHToUInt(io.chosenOH)
}

class StreamArbiterFactory {
  var arbitrationLogic: (StreamArbiter[_ <: Data]) => Area = StreamArbiter.Arbitration.lowerFirst
  var lockLogic: (StreamArbiter[_ <: Data]) => Area = StreamArbiter.Lock.transactionLock

  def build[T <: Data](dataType: HardType[T], portCount: Int): StreamArbiter[T] = {
    new StreamArbiter(dataType, portCount)(arbitrationLogic, lockLogic)
  }

  def onArgs[T <: Data](inputs: Stream[T]*): Stream[T] = on(inputs.seq)
  def on[T <: Data](inputs: Seq[Stream[T]]): Stream[T] = {
    val arbiter = build(inputs(0).payloadType, inputs.size)
    (arbiter.io.inputs, inputs).zipped.foreach(_ << _)
    return arbiter.io.output
  }

  def lowerFirst: this.type = {
    arbitrationLogic = StreamArbiter.Arbitration.lowerFirst
    this
  }
  def roundRobin: this.type = {
    arbitrationLogic = StreamArbiter.Arbitration.roundRobin
    this
  }
  def sequentialOrder: this.type = {
    arbitrationLogic = StreamArbiter.Arbitration.sequentialOrder
    this
  }
  def noLock: this.type = {
    lockLogic = StreamArbiter.Lock.none
    this
  }
  def fragmentLock: this.type = {
    lockLogic = StreamArbiter.Lock.fragmentLock
    this
  }
  def transactionLock: this.type = {
    lockLogic = StreamArbiter.Lock.transactionLock
    this
  }
}

/** This is equivalent to a StreamDemux, but with a counter attached to the port selector.
  */
// TODOTEST
object StreamDispatcherSequential {
  def apply[T <: Data](input: Stream[T], outputCount: Int): Vec[Stream[T]] = {
    val select = Counter(outputCount)
    when(input.fire) {
      select.increment()
    }
    StreamDemux(input, select, outputCount)
  }
}

/** @deprecated Do not use
  */
// TODOTEST
object StreamDispatcherSequencial {
  def apply[T <: Data](input: Stream[T], outputCount: Int): Vec[Stream[T]] = {
    StreamDispatcherSequential(input, outputCount)
  }
}

/** @deprecated Do not use. Use the companion object or a normal regular StreamMux instead.
  */
class StreamDispatcherSequencial[T <: Data](gen: HardType[T], n: Int) extends Component {
  val io = new Bundle {
    val input = slave Stream (gen)
    val outputs = Vec(master Stream (gen), n)
  }
  val counter = Counter(n, io.input.fire)

  if (n == 1) {
    io.input >> io.outputs(0)
  } else {
    io.input.ready := False
    for (i <- 0 to n - 1) {
      io.outputs(i).payload := io.input.payload
      when(counter =/= i) {
        io.outputs(i).valid := False
      } otherwise {
        io.outputs(i).valid := io.input.valid
        io.input.ready := io.outputs(i).ready
      }
    }
  }
}

/** Combine a stream and a flow to a new stream. If both input sources fire, the flow will be preferred. */
object StreamFlowArbiter {
  def apply[T <: Data](inputStream: Stream[T], inputFlow: Flow[T]): Flow[T] = {
    val output = cloneOf(inputFlow)

    output.valid := inputFlow.valid || inputStream.valid
    inputStream.ready := !inputFlow.valid
    output.payload := Mux(inputFlow.valid, inputFlow.payload, inputStream.payload)

    output
  }
}

//Give priority to the inputFlow
class StreamFlowArbiter[T <: Data](dataType: T) extends Area {
  val io = new Bundle {
    val inputFlow = slave Flow (dataType)
    val inputStream = slave Stream (dataType)
    val output = master Flow (dataType)
  }
  io.output.valid := io.inputFlow.valid || io.inputStream.valid
  io.inputStream.ready := !io.inputFlow.valid
  io.output.payload := Mux(io.inputFlow.valid, io.inputFlow.payload, io.inputStream.payload)
}

object StreamFork {
  def apply[T <: Data](input: Stream[T], portCount: Int, synchronous: Boolean = false): Vec[Stream[T]] = {
    val fork = new StreamFork(input.payloadType, portCount, synchronous).setCompositeName(input, "fork", true)
    fork.io.input << input
    return fork.io.outputs
  }
}

object StreamFork2 {
  def apply[T <: Data](input: Stream[T], synchronous: Boolean = false): (Stream[T], Stream[T]) = {
    val fork = new StreamFork(input.payloadType, 2, synchronous).setCompositeName(input, "fork", true)
    fork.io.input << input
    return (fork.io.outputs(0), fork.io.outputs(1))
  }
}

object StreamFork3 {
  def apply[T <: Data](input: Stream[T], synchronous: Boolean = false): (Stream[T], Stream[T], Stream[T]) = {
    val fork = new StreamFork(input.payloadType, 3, synchronous).setCompositeName(input, "fork", true)
    fork.io.input << input
    return (fork.io.outputs(0), fork.io.outputs(1), fork.io.outputs(2))
  }
}

/** A StreamFork will clone each incoming data to all its output streams. If synchronous is true,
  *  all output streams will always fire together, which means that the stream will halt until all
  *  output streams are ready. If synchronous is false, output streams may be ready one at a time,
  *  at the cost of an additional flip flop (1 bit per output). The input stream will block until
  *  all output streams have processed each item regardlessly.
  *
  *  Note that this means that when synchronous is true, the valid signal of the outputs depends on
  *  their inputs, which may lead to dead locks when used in combination with systems that have it the
  *  other way around. It also violates the handshake of the AXI specification (section A3.3.1).
  */
//TODOTEST
class StreamFork[T <: Data](dataType: HardType[T], portCount: Int, synchronous: Boolean = false) extends Component {
  val io = new Bundle {
    val input = slave Stream (dataType)
    val outputs = Vec(master Stream (dataType), portCount)
  }
  if (synchronous) {
    io.input.ready := io.outputs.map(_.ready).reduce(_ && _)
    io.outputs.foreach(_.valid := io.input.valid && io.input.ready)
    io.outputs.foreach(_.payload := io.input.payload)
  } else {
    /* Store if an output stream already has taken its value or not */
    val linkEnable = Vec(RegInit(True), portCount)

    /* Ready is true when every output stream takes or has taken its value */
    io.input.ready := True
    for (i <- 0 until portCount) {
      when(!io.outputs(i).ready && linkEnable(i)) {
        io.input.ready := False
      }
    }

    /* Outputs are valid if the input is valid and they haven't taken their value yet.
     * When an output fires, mark its value as taken. */
    for (i <- 0 until portCount) {
      io.outputs(i).valid := io.input.valid && linkEnable(i)
      io.outputs(i).payload := io.input.payload
      when(io.outputs(i).fire) {
        linkEnable(i) := False
      }
    }

    /* Reset the storage for each new value */
    when(io.input.ready) {
      linkEnable.foreach(_ := True)
    }
  }
}

object StreamTransactionCounter {
  def apply[T <: Data, T2 <: Data](
      trigger: Stream[T],
      target: Stream[T2],
      count: UInt,
      noDelay: Boolean = false
  ): StreamTransactionCounter = {
    val inst = new StreamTransactionCounter(count.getWidth, noDelay)
    inst.io.ctrlFire := trigger.fire
    inst.io.targetFire := target.fire
    inst.io.count := count
    inst
  }
}

class StreamTransactionCounter(
    countWidth: Int,
    noDelay: Boolean = false
) extends Component {
  val io = new Bundle {
    val ctrlFire = in Bool ()
    val targetFire = in Bool ()
    val count = in UInt (countWidth bits)
    val working = out Bool ()
    val last = out Bool ()
    val done = out Bool ()
    val value = out UInt (countWidth bit)
  }

  val countReg = RegNextWhen(io.count, io.ctrlFire)
  val counter = Counter(io.count.getBitsWidth bits)
  val expected = cloneOf(io.count)
  expected := countReg

  val lastOne = counter === expected
  val running = Reg(Bool()) init False

  val done = lastOne && io.targetFire
  val doneWithFire = if (noDelay) False else True
  when(done && io.ctrlFire) {
    running := doneWithFire
  } elsewhen (io.ctrlFire) {
    running := True
  } elsewhen done {
    running := False
  }

  when(done) {
    counter.clear()
  } elsewhen (io.targetFire) {
    counter.increment()
  }

  if (noDelay) {
    when(io.ctrlFire) {
      expected := io.count
    }
  }

  io.working := running
  io.last := lastOne
  io.done := lastOne && io.targetFire
  io.value := counter
}

object StreamTransactionExtender {
  def apply[T <: Data](input: Stream[T], count: UInt)(
      driver: (UInt, T, Bool) => T = (_: UInt, p: T, _: Bool) => p
  ): Stream[T] = {
    val c = new StreamTransactionExtender(input.payloadType, input.payloadType, count.getBitsWidth, driver)
    c.io.input << input
    c.io.count := count
    c.io.output
  }

  def apply[T <: Data, T2 <: Data](input: Stream[T], output: Stream[T2], count: UInt)(
      driver: (UInt, T, Bool) => T2
  ): StreamTransactionExtender[T, T2] = {
    val c = new StreamTransactionExtender(input.payloadType, output.payloadType, count.getBitsWidth, driver)
    c.io.input << input
    c.io.count := count
    output << c.io.output
    c
  }
}

/* Extend one input transfer into serveral outputs, io.count represent delivering output (count + 1) times. */
class StreamTransactionExtender[T <: Data, T2 <: Data](
    dataType: HardType[T],
    outDataType: HardType[T2],
    countWidth: Int,
    driver: (UInt, T, Bool) => T2
) extends Component {
  val io = new Bundle {
    val count = in UInt (countWidth bit)
    val input = slave Stream dataType
    val output = master Stream outDataType
    val working = out Bool ()
    val first = out Bool ()
    val last = out Bool ()
    val done = out Bool ()
  }

  val counter = StreamTransactionCounter(io.input, io.output, io.count)
  val payload = Reg(io.input.payloadType)
  val lastOne = counter.io.last
  val outValid = RegInit(False)

  when(counter.io.done) {
    outValid := False
  }

  when(io.input.fire) {
    payload := io.input.payload
    outValid := True
  }

  io.output.payload := driver(counter.io.value, payload, lastOne)
  io.output.valid := outValid
  io.input.ready := (!outValid || counter.io.done)
  io.last := lastOne
  io.done := counter.io.done
  io.first := (counter.io.value === 0) && counter.io.working
  io.working := counter.io.working
}
