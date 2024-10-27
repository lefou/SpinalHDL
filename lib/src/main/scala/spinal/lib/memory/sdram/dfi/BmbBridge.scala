package spinal.lib.memory.sdram.dfi

import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb.{Bmb, BmbParameter}
import spinal.lib.memory.sdram.dfi.function._
import spinal.lib.memory.sdram.dfi.interface._

case class BmbBridge(bmbp: BmbParameter, taskConfig: TaskConfig, dfiConfig: DfiConfig) extends Component {

  val io = new Bundle {
    val bmb = slave(Bmb(bmbp))
    val taskPort = master(TaskPort(taskConfig, dfiConfig))
  }

  val bmbAdapter = BmbAdapter(bmbp, taskConfig, dfiConfig)
  bmbAdapter.io.input <> io.bmb
  bmbAdapter.io.output.writeData >-> io.taskPort.writeData
  bmbAdapter.io.output.rsp <-< io.taskPort.rsp

  val maketask = MakeTask(taskConfig, dfiConfig)
  maketask.io.cmd <-< bmbAdapter.io.output.cmd
  maketask.io.writeDataToken <-< bmbAdapter.io.output.writeDataToken
  maketask.io.output <> io.taskPort.tasks
  RegNext(maketask.io.halt) <> bmbAdapter.io.halt
}
