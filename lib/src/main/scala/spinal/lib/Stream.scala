package spinal.lib

import spinal.core._

trait StreamPipe {
  def apply[T <: Data](m: Stream[T]): Stream[T]
}

object StreamPipe {
  val NONE = new StreamPipe {
    override def apply[T <: Data](m: Stream[T]) = m.combStage()
  }
  val M2S = new StreamPipe {
    override def apply[T <: Data](m: Stream[T]) = m.m2sPipe()
  }
  val S2M = new StreamPipe {
    override def apply[T <: Data](m: Stream[T]) = m.s2mPipe()
  }
  val FULL = new StreamPipe {
    override def apply[T <: Data](m: Stream[T]) = m.s2mPipe().m2sPipe()
  }
  val HALF = new StreamPipe {
    override def apply[T <: Data](m: Stream[T]) = m.halfPipe()
  }
}

class StreamFactory extends MSFactory {
  object Fragment extends StreamFragmentFactory

  def apply[T <: Data](hardType: HardType[T]) = {
    val ret = new Stream(hardType)
    postApply(ret)
    ret
  }

  def apply[T <: Data](hardType: => T) : Stream[T] = apply(HardType(hardType))
}

object Stream extends StreamFactory

class EventFactory extends MSFactory {
  def apply = {
    val ret = new Stream(new NoData)
    postApply(ret)
    ret
  }
}

case class EventEmitter(on : Event){
  val reg = RegInit(False)
  when(on.ready){
    reg := False
  }
  on.valid := reg

  def emit(): Unit ={
    reg := True
  }
}

class Stream[T <: Data](val payloadType :  HardType[T]) extends Bundle with IMasterSlave with DataCarrier[T] {
  val valid   = Bool()
  val ready   = Bool()
  val payload = payloadType()

  override def clone: Stream[T] =  Stream(payloadType)

  override def asMaster(): Unit = {
    out(valid)
    in(ready)
    out(payload)
  }


  def asDataStream = this.asInstanceOf[Stream[Data]]
  override def freeRun(): this.type = {
    ready := True
    this
  }

/** @return Return a flow drived by this stream. Ready of ths stream is always high
  */
  def toFlow: Flow[T] = {
    freeRun()
    val ret = Flow(payloadType)
    ret.valid := this.valid
    ret.payload := this.payload
    ret
  }

  def toFlowFire: Flow[T] = {
    val ret = Flow(payloadType)
    ret.valid := this.fire
    ret.payload := this.payload
    ret
  }

  def asFlow: Flow[T] = {
    val ret = Flow(payloadType)
    ret.valid := this.valid
    ret.payload := this.payload
    ret
  }

  /** Connect that to this
  */
  def <<(that: Stream[T]): Stream[T] = connectFrom(that)

/** Connect this to that
  */
  def >>(into: Stream[T]): Stream[T] = {
    into << this
    into
  }

/** Connect that to this. The valid/payload path are cut by an register stage
  */
  def <-<(that: Stream[T]): Stream[T] = {
    this << that.stage()
    that
  }

/** Connect this to that. The valid/payload path are cut by an register stage
  */
  def >->(into: Stream[T]): Stream[T] = {
    into <-< this
    into
  }

/** Connect that to this. The ready path is cut by an register stage
  */
  def </<(that: Stream[T]): Stream[T] = {
    this << that.s2mPipe()
    that
  }

/** Connect this to that. The ready path is cut by an register stage
  */
  def >/>(that: Stream[T]): Stream[T] = {
    that </< this
    that
  }

/** Connect that to this. The valid/payload/ready path are cut by an register stage
  */
  def <-/<(that: Stream[T]): Stream[T] = {
    this << that.s2mPipe().m2sPipe()
    that
  }

/** Connect that to this. The valid/payload/ready path are cut by an register stage
  */
  def >/->(into: Stream[T]): Stream[T] = {
    into <-/< this;
    into
  }

  def pipelined(pipe: StreamPipe) = pipe(this)
  def pipelined(m2s : Boolean = false,
                s2m : Boolean = false,
                halfRate : Boolean = false) : Stream[T] = {
    (m2s, s2m, halfRate) match {
      case (false,false,false) => StreamPipe.NONE(this)
      case (true,false,false) =>  StreamPipe.M2S(this)
      case (false,true,false) =>  StreamPipe.S2M(this)
      case (true,true,false) =>   StreamPipe.FULL(this)
      case (false,false,true) =>  StreamPipe.HALF(this)
    }
  }

  def &(cond: Bool): Stream[T] = continueWhen(cond)
  def ~[T2 <: Data](that: T2): Stream[T2] = translateWith(that)
  def ~~[T2 <: Data](translate: (T) => T2): Stream[T2] = map(translate)
  def map[T2 <: Data](translate: (T) => T2): Stream[T2] = {
    (this ~ translate(this.payload))
  }

/** Ignore the payload */
  def toEvent() : Event = {
    val ret = Event
    ret.arbitrationFrom(this)
    ret
  }

/** Connect this to a fifo and return its pop stream
  */
  def queue(size: Int): Stream[T] = new Composite(this){
    val fifo = new StreamFifo(payloadType, size)
    fifo.io.push << self
  }.fifo.io.pop

/** Connect this to an clock crossing fifo and return its pop stream
  */
  def queue(size: Int, pushClock: ClockDomain, popClock: ClockDomain): Stream[T] = {
    val fifo = new StreamFifoCC(payloadType, size, pushClock, popClock).setCompositeName(this,"queue", true)
    fifo.io.push << this
    return fifo.io.pop
  }

/** Connect this to a fifo and return its pop stream and its occupancy
  */
  def queueWithOccupancy(size: Int): (Stream[T], UInt) = {
    val fifo = new StreamFifo(payloadType, size).setCompositeName(this,"queueWithOccupancy", true)
    fifo.io.push << this
    return (fifo.io.pop, fifo.io.occupancy)
  }

  def queueWithAvailability(size: Int): (Stream[T], UInt) = {
    val fifo = new StreamFifo(payloadType, size).setCompositeName(this,"queueWithAvailability", true)
    fifo.io.push << this
    return (fifo.io.pop, fifo.io.availability)
  }

/** Connect this to a cross clock domain fifo and return its pop stream and its push side occupancy
  */
  def queueWithPushOccupancy(size: Int, pushClock: ClockDomain, popClock: ClockDomain): (Stream[T], UInt) = {
    val fifo = new StreamFifoCC(payloadType, size, pushClock, popClock).setCompositeName(this,"queueWithPushOccupancy", true)
    fifo.io.push << this
    return (fifo.io.pop, fifo.io.pushOccupancy)
  }


  /** Connect this to a zero latency fifo and return its pop stream
    */
  def queueLowLatency(size: Int, latency : Int = 0): Stream[T] = {
    val fifo = new StreamFifoLowLatency(payloadType, size, latency)
    fifo.setPartialName(this, "fifo", true)
    fifo.io.push << this
    fifo.io.pop
  }

  def ccToggle(pushClock: ClockDomain, popClock: ClockDomain): Stream[T] = {
    val cc = new StreamCCByToggle(payloadType, pushClock, popClock).setCompositeName(this,"ccToggle", true)
    cc.io.input << this
    cc.io.output
  }

  def ccToggleWithoutBuffer(pushClock: ClockDomain, popClock: ClockDomain): Stream[T] = {
    val cc = new StreamCCByToggle(payloadType, pushClock, popClock, withOutputBuffer=false, withInputWait=true).setCompositeName(this,"ccToggle", true)
    cc.io.input << this
    cc.io.output
  }


  /**
   * Connect this to a new stream that only advances every n elements, thus repeating the input several times.
   * @return A tuple with the resulting stream that duplicates the items and the counter, indicating how many
   *				 times the current element has been repeated.
   */
  def repeat(times: Int): (Stream[T], UInt) = {
    val ret = Stream(payloadType)
    val counter = Counter(times, ret.fire)
    ret.valid := this.valid
    ret.payload := this.payload
    this.ready := ret.ready && counter.willOverflowIfInc
    (ret, counter)
  }
  
  /**
   * Connect this to a new stream whose payload is n times as wide, but that only fires every n cycles.
   * It introduces 0 to factor-1 cycles of latency. Mapping a stream into memory and mapping a slowed
   * down stream into memory should yield the same result, thus the elements of the input will be
   * written from high bits to low bits.
   */
  def slowdown(factor: Int): Stream[Vec[T]] = {
    val next = new Stream(Vec(payloadType(), factor)).setCompositeName(this, "slowdown_x" + factor, true)
    next.payload(0) := this.payload
    for (i <- 1 until factor) {
      next.payload(i) := RegNextWhen(next.payload(i - 1), this.fire)
    }
    val counter = Counter(factor)
    when(this.fire) {
      counter.increment()
    }
    when(counter.willOverflowIfInc) {
      this.ready := next.ready
      next.valid := this.valid
    } otherwise {
      this.ready := True
      next.valid := False
    }
    next
  }

/** Return True when a transaction is present on the bus but the ready signal is low
    */
  def isStall : Bool = (valid && !ready).setCompositeName(this, "isStall", true)

  /** Return True when a transaction has appeared (first cycle)
    */
  def isNew : Bool = (valid && !(RegNext(isStall) init(False))).setCompositeName(this, "isNew", true)

  /** Return True when a transaction occurs on the bus (valid && ready)
  */
  override def fire: Bool = (valid & ready).setCompositeName(this, "fire", true)

/** Return True when the bus is ready, but no data is present
  */
  def isFree: Bool = (!valid || ready).setCompositeName(this, "isFree", true)
  
  def connectFrom(that: Stream[T]): Stream[T] = {
    this.valid := that.valid
    that.ready := this.ready
    this.payload := that.payload
    that
  }

  /** Drive arbitration signals of this from that
    */
  def arbitrationFrom[T2 <: Data](that : Stream[T2]) : Unit = {
    this.valid := that.valid
    that.ready := this.ready
  }

  def translateFrom[T2 <: Data](that: Stream[T2])(dataAssignment: (T, that.payload.type) => Unit): Stream[T] = {
    this.valid := that.valid
    that.ready := this.ready
    dataAssignment(this.payload, that.payload)
    this
  }

  def translateInto[T2 <: Data](into: Stream[T2])(dataAssignment: (T2, T) => Unit): Stream[T2] = {
    into.translateFrom(this)(dataAssignment)
    into
  }

/** Replace this stream's payload with another one
  */
  def translateWith[T2 <: Data](that: T2): Stream[T2] = {
    val next = new Stream(that).setCompositeName(this, "translated", true)
    next.arbitrationFrom(this)
    next.payload := that
    next
  }

/** Change the payload's content type. The new type must have the same bit length as the current one.
  */
  def transmuteWith[T2 <: Data](that: HardType[T2]) = {
    val next = new Stream(that).setCompositeName(this, "transmuted", true)
    next.arbitrationFrom(this)
    next.payload.assignFromBits(this.payload.asBits)
    next
  }


  def swapPayload[T2 <: Data](that: HardType[T2]) = {
    val next = new Stream(that).setCompositeName(this, "swap", true)
    next.arbitrationFrom(this)
    next
  }


  /** A combinatorial stage doesn't do anything, but it is nice to separate signals for combinatorial transformations.
  */
  def combStage() : Stream[T] = {
    val ret = Stream(payloadType).setCompositeName(this, "combStage", true)
    ret << this
    ret
  }

  /** Connect this to a valid/payload register stage and return its output stream
  */
  def stage() : Stream[T] = this.m2sPipe()

  //! if collapsBubble is enable then ready is not "don't care" during valid low !
  def m2sPipe(collapsBubble : Boolean = true, crossClockData: Boolean = false, flush : Bool = null, holdPayload : Boolean = false): Stream[T] = new Composite(this) {
    val m2sPipe = Stream(payloadType)

    val rValid = RegNextWhen(self.valid, self.ready) init(False)
    val rData = RegNextWhen(self.payload, if(holdPayload) self.fire else self.ready)

    if (crossClockData) rData.addTag(crossClockDomain)
    if (flush != null) rValid clearWhen(flush)

    self.ready := m2sPipe.ready
    if (collapsBubble) self.ready setWhen(!m2sPipe.valid)

    m2sPipe.valid := rValid
    m2sPipe.payload := rData
  }.m2sPipe

  def s2mPipe(): Stream[T] = new Composite(this) {
    val s2mPipe = Stream(payloadType)

    val rValid = RegInit(False) setWhen(self.valid) clearWhen(s2mPipe.ready)
    val rData = RegNextWhen(self.payload, self.ready)

    self.ready := !rValid

    s2mPipe.valid := self.valid || rValid
    s2mPipe.payload := Mux(rValid, rData, self.payload)
  }.s2mPipe

  def s2mPipe(stagesCount : Int): Stream[T] = {
    stagesCount match {
      case 0 => this
      case _ => this.s2mPipe().s2mPipe(stagesCount-1)
    }
  }

  def validPipe() : Stream[T] = new Composite(this) {
    val validPipe = Stream(payloadType)

    val rValid = RegInit(False) setWhen(self.valid) clearWhen(validPipe.fire)

    self.ready := validPipe.fire

    validPipe.valid := rValid
    validPipe.payload := self.payload
  }.validPipe

/** cut all path, but divide the bandwidth by 2, 1 cycle latency
  */
  def halfPipe(): Stream[T] = new Composite(this) {
    val halfPipe = Stream(payloadType)

    val rValid = RegInit(False) setWhen(self.valid) clearWhen(halfPipe.fire)
    val rData = RegNextWhen(self.payload, self.ready)

    self.ready := !rValid

    halfPipe.valid := rValid
    halfPipe.payload := rData
  }.halfPipe

/** Block this when cond is False. Return the resulting stream
  */
  def continueWhen(cond: Bool): Stream[T] = {
    val next = new Stream(payloadType)
    next.valid := this.valid && cond
    this.ready := next.ready && cond
    next.payload := this.payload
    return next
  }

/** Drop transactions of this when cond is True. Return the resulting stream
  */
  def throwWhen(cond: Bool): Stream[T] = {
    val next = Stream(payloadType).setCompositeName(this, "thrown", true)

    next << this
    when(cond) {
      next.valid := False
      this.ready := True
    }
    next
  }

  def clearValidWhen(cond : Bool): Stream[T] = {
    val next = Stream(payloadType).setCompositeName(this, "clearValidWhen", true)
    next.valid := this.valid && !cond
    next.payload := this.payload
    this.ready := next.ready
    next
  }

  /** Stop transactions on this when cond is True. Return the resulting stream
    */
  def haltWhen(cond: Bool): Stream[T] = continueWhen(!cond)

/** Drop transaction of this when cond is False. Return the resulting stream
  */
  def takeWhen(cond: Bool): Stream[T] = throwWhen(!cond)


  def fragmentTransaction(bitsWidth: Int): Stream[Fragment[Bits]] = {
    val converter = new StreamToStreamFragmentBits(payload, bitsWidth)
    converter.io.input << this
    return converter.io.output
  }
  
  /**
   * Convert this stream to a fragmented stream by adding a last bit. To view it from
   * another perspective, bundle together successive events as fragments of a larger whole.
   * You can then use enhanced operations on fragmented streams, like reducing of elements.
   */
  def addFragmentLast(last : Bool) : Stream[Fragment[T]] = {
    val ret = Stream(Fragment(payloadType))
    ret.arbitrationFrom(this)
    ret.last := last
    ret.fragment := this.payload
    return ret
  }
  
  /** 
   *  Like addFragmentLast(Bool), but instead of manually telling which values go together,
   *  let a counter do the job. The counter will increment for each passing element. Last
   *  will be set high at the end of each revolution.
	 * @example {{{ outStream = inStream.addFragmentLast(new Counter(5)) }}}
   */
  def addFragmentLast(counter: Counter) : Stream[Fragment[T]] = {
    when (this.fire) {
      counter.increment()
    }
    val last = counter.willOverflowIfInc
    return addFragmentLast(last)
  }
  
  def setIdle(): this.type = {
    this.valid := False
    this.payload.assignDontCare()
    this
  }
  
  def setBlocked(): this.type = {
    this.ready := False
    this
  }

  def forkSerial(cond : Bool): Stream[T] = new Composite(this, "forkSerial"){
    val next = Stream(payloadType)
    next.valid := self.valid
    next.payload := self.payload
    self.ready := next.ready && cond
  }.next

  override def getTypeString = getClass.getSimpleName + "[" + this.payload.getClass.getSimpleName + "]"
}

object StreamWidthAdapter {
  def apply[T <: Data,T2 <: Data](input : Stream[T],output : Stream[T2], endianness: Endianness = LITTLE, padding : Boolean = false): Unit = {
    val inputWidth = widthOf(input.payload)
    val outputWidth = widthOf(output.payload)
    if(inputWidth == outputWidth){
      output.arbitrationFrom(input)
      output.payload.assignFromBits(input.payload.asBits)
    } else if(inputWidth > outputWidth){
      require(inputWidth % outputWidth == 0 || padding)
      val factor = (inputWidth + outputWidth - 1) / outputWidth
      val paddedInputWidth = factor * outputWidth
      val counter = Counter(factor,inc = output.fire)
      output.valid := input.valid
      endianness match {
        case `LITTLE` => output.payload.assignFromBits(input.payload.asBits.resize(paddedInputWidth).subdivideIn(factor slices).read(counter))
        case `BIG`    => output.payload.assignFromBits(input.payload.asBits.resize(paddedInputWidth).subdivideIn(factor slices).reverse.read(counter))
      }
      input.ready := output.ready && counter.willOverflowIfInc
    } else{
      require(outputWidth % inputWidth == 0 || padding)
      val factor  = (outputWidth + inputWidth - 1) / inputWidth
      val paddedOutputWidth = factor * inputWidth
      val counter = Counter(factor,inc = input.fire)
      val buffer  = Reg(Bits(paddedOutputWidth - inputWidth bits))
      when(input.fire){
        buffer := input.payload ## (buffer >> inputWidth)
      }
      output.valid := input.valid && counter.willOverflowIfInc
      endianness match {
        case `LITTLE` => output.payload.assignFromBits((input.payload ## buffer).resized)
        case `BIG`    => output.payload.assignFromBits((input.payload ## buffer).subdivideIn(factor slices).reverse.asBits().resized)
      }
      input.ready := !(!output.ready && counter.willOverflowIfInc)
    }
  }

  def make[T <: Data, T2 <: Data](input : Stream[T], outputPayloadType : HardType[T2], endianness: Endianness = LITTLE, padding : Boolean = false) : Stream[T2] = {
    val ret = Stream(outputPayloadType())
    StreamWidthAdapter(input,ret,endianness,padding)
    ret
  }

  def main(args: Array[String]) : Unit = {
    SpinalVhdl(new Component{
      val input = slave(Stream(Bits(4 bits)))
      val output = master(Stream(Bits(32 bits)))
      StreamWidthAdapter(input,output)
    })
  }
}

object StreamFragmentWidthAdapter {
  def apply[T <: Data,T2 <: Data](input : Stream[Fragment[T]],output : Stream[Fragment[T2]], endianness: Endianness = LITTLE, padding : Boolean = false): Unit = {
    val inputWidth = widthOf(input.fragment)
    val outputWidth = widthOf(output.fragment)
    if(inputWidth == outputWidth){
      output.arbitrationFrom(input)
      output.payload.assignFromBits(input.payload.asBits)
    } else if(inputWidth > outputWidth){
      require(inputWidth % outputWidth == 0 || padding)
      val factor = (inputWidth + outputWidth - 1) / outputWidth
      val paddedInputWidth = factor * outputWidth
      val counter = Counter(factor,inc = output.fire)
      output.valid := input.valid
      endianness match {
        case `LITTLE` => output.fragment.assignFromBits(input.fragment.asBits.resize(paddedInputWidth).subdivideIn(factor slices).read(counter))
        case `BIG`    => output.fragment.assignFromBits(input.fragment.asBits.resize(paddedInputWidth).subdivideIn(factor slices).reverse.read(counter))
      }
      output.last := input.last && counter.willOverflowIfInc
      input.ready := output.ready && counter.willOverflowIfInc
    } else{
      require(outputWidth % inputWidth == 0 || padding)
      val factor  = (outputWidth + inputWidth - 1) / inputWidth
      val paddedOutputWidth = factor * inputWidth
      val counter = Counter(factor,inc = input.fire)
      val buffer  = Reg(Bits(paddedOutputWidth - inputWidth bits))
      when(input.fire){
        buffer := input.fragment ## (buffer >> inputWidth)
      }
      output.valid := input.valid && counter.willOverflowIfInc
      endianness match {
        case `LITTLE` => output.fragment.assignFromBits((input.fragment ## buffer).resized)
        case `BIG`    => output.fragment.assignFromBits((input.fragment ## buffer).subdivideIn(factor slices).reverse.asBits().resized)
      }
      output.last := input.last
      input.ready := !(!output.ready && counter.willOverflowIfInc)
    }
  }

  def make[T <: Data, T2 <: Data](input : Stream[Fragment[T]], outputPayloadType : HardType[T2], endianness: Endianness = LITTLE, padding : Boolean = false) : Stream[Fragment[T2]] = {
    val ret = Stream(Fragment(outputPayloadType()))
    StreamFragmentWidthAdapter(input,ret,endianness,padding)
    ret
  }
}

object StreamCCByToggle {
  def apply[T <: Data](input: Stream[T], inputClock: ClockDomain, outputClock: ClockDomain): Stream[T] = {
    val c = new StreamCCByToggle[T](input.payload, inputClock, outputClock)
    c.io.input << input
    return c.io.output
  }

  def apply[T <: Data](dataType: T, inputClock: ClockDomain, outputClock: ClockDomain): StreamCCByToggle[T] = {
    new StreamCCByToggle[T](dataType, inputClock, outputClock)
  }
}

class StreamCCByToggle[T <: Data](dataType: HardType[T], 
                                  inputClock: ClockDomain, 
                                  outputClock: ClockDomain, 
                                  withOutputBuffer : Boolean = true,
                                  withInputWait : Boolean = false,
                                  withOutputBufferedReset : Boolean = true) extends Component {
  val io = new Bundle {
    val input = slave Stream (dataType())
    val output = master Stream (dataType())
  }

  val outHitSignal = Bool()

  val pushArea = inputClock on new Area {
    val hit = BufferCC(outHitSignal, False)
    val accept = Bool()
    val target = RegInit(False) toggleWhen(accept)
    val data = RegNextWhen(io.input.payload, accept)

    if (!withInputWait) {
      accept := io.input.fire
      io.input.ready := (hit === target)
    } else {
      val busy = RegInit(False) setWhen(accept) clearWhen(io.input.ready)
      accept := (!busy) && io.input.valid
      io.input.ready := busy && (hit === target)
    }
  }

  val finalOutputClock = outputClock.withOptionalBufferedResetFrom(withOutputBufferedReset)(inputClock)
  val popArea = finalOutputClock on new Area {
    val stream = cloneOf(io.input)

    val target = BufferCC(pushArea.target, False)
    val hit = RegNextWhen(target, stream.fire) init(False)
    outHitSignal := hit

    stream.valid := (target =/= hit)

    stream.payload := pushArea.data
    stream.payload.addTag(crossClockDomain)

    io.output << (if(withOutputBuffer) stream.m2sPipe(holdPayload = true) else stream)
  }
}