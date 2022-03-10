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
