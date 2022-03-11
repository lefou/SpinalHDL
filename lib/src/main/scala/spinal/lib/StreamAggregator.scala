package spinal.lib

import spinal.core._
import scala.collection.Seq

/**  Multiplex multiple streams into a single one, always only processing one at a time.
  */
object StreamMux {
  def apply[T <: Data](select: UInt, inputs: Seq[Stream[T]]): Stream[T] = {
    val vec = Vec(inputs)
    StreamMux(select, vec)
  }

  def apply[T <: Data](select: UInt, inputs: Vec[Stream[T]]): Stream[T] = {
    val c = new StreamMux(inputs(0).payload, inputs.length)
    (c.io.inputs, inputs).zipped.foreach(_ << _)
    c.io.select := select
    c.io.output
  }
}

class StreamMux[T <: Data](dataType: T, portCount: Int) extends Component {
  val io = new Bundle {
    val select = in UInt (log2Up(portCount) bit)
    val inputs = Vec(slave Stream (dataType), portCount)
    val output = master Stream (dataType)
  }
  for ((input, index) <- io.inputs.zipWithIndex) {
    input.ready := io.select === index && io.output.ready
  }
  io.output.valid := io.inputs(io.select).valid
  io.output.payload := io.inputs(io.select).payload
}

/** This is equivalent to a StreamMux, but with a counter attached to the port selector.
  */
// TODOTEST
object StreamCombinerSequential {
  def apply[T <: Data](inputs: Seq[Stream[T]]): Stream[T] = {
    val select = Counter(inputs.length)
    val stream = StreamMux(select, inputs)
    when(stream.fire) {
      select.increment()
    }
    stream
  }
}

/** Join multiple streams into one. The resulting stream will only fire if all of them fire, so you may want to buffer the inputs. */
object StreamJoin {

  /** Convert a tuple of streams into a stream of tuples
    */
  def apply[T1 <: Data, T2 <: Data](source1: Stream[T1], source2: Stream[T2]): Stream[TupleBundle2[T1, T2]] = {
    val sources = Seq(source1, source2)
    val combined = Stream(
      TupleBundle2(
        source1.payloadType,
        source2.payloadType
      )
    )
    combined.valid := sources.map(_.valid).reduce(_ && _)
    sources.foreach(_.ready := combined.fire)
    combined.payload._1 := source1.payload
    combined.payload._2 := source2.payload
    combined
  }

  /** Convert a vector of streams into a stream of vectors.
    */
  def vec[T <: Data](sources: Seq[Stream[T]]): Stream[Vec[T]] = {
    val payload = Vec(sources.map(_.payload))
    val combined = Stream(payload)
    combined.payload := payload
    combined.valid := sources.map(_.valid).reduce(_ && _)
    sources.foreach(_.ready := combined.fire)
    combined
  }

  def arg(sources: Stream[_]*): Event = apply(sources.seq)

  /** Join streams, but ignore the payload of the input streams. */
  def apply(sources: Seq[Stream[_]]): Event = {
    val event = Event
    val eventFire = event.fire
    event.valid := sources.map(_.valid).reduce(_ && _)
    sources.foreach(_.ready := eventFire)
    event
  }

  /** Join streams, but ignore the payload and replace it with a custom one.
    * @param payload The payload of the resulting stream
    */
  def fixedPayload[T <: Data](sources: Seq[Stream[_]], payload: T): Stream[T] =
    StreamJoin(sources).translateWith(payload)
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
