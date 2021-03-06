package outlier

import common_utils.Data
import common_utils.Utils._
import org.apache.flink.api.common.state.{ValueState, ValueStateDescriptor}
import org.apache.flink.streaming.api.scala.function.ProcessWindowFunction
import org.apache.flink.streaming.api.windowing.windows.TimeWindow
import org.apache.flink.util.Collector

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

case class McodState(var PD: mutable.HashMap[Int, Data], var MC: mutable.HashMap[Int, MicroCluster])

case class MicroCluster(var center: ListBuffer[Double], var points: ListBuffer[Data])

class Pmcod(time_slide: Int, range: Double, k_c: Int) extends ProcessWindowFunction[(Int, Data), (Long, Int), Int, TimeWindow] {

  val slide = time_slide
  val R = range
  val k = k_c
  var mc_counter = 1

  lazy val state: ValueState[McodState] = getRuntimeContext
    .getState(new ValueStateDescriptor[McodState]("myState", classOf[McodState]))

  override def process(key: Int, context: Context, elements: Iterable[(Int, Data)], out: Collector[(Long, Int)]): Unit = {

    val window = context.window

    //create state
    if (state.value == null) {
      val PD = mutable.HashMap[Int, Data]()
      val MC = mutable.HashMap[Int, MicroCluster]()
      val current = McodState(PD, MC)
      state.update(current)
    }

    //insert new elements
    elements
      .filter(_._2.arrival >= window.getEnd - slide)
      .foreach(p => insertPoint(p._2, true))

    //Find outliers
    var outliers = 0
    state.value().PD.values.foreach(p => {
      if (!p.safe_inlier && p.flag == 0)
        if (p.count_after + p.nn_before.count(_ >= window.getStart) < k) {
          outliers += 1
        }
    })

    out.collect((window.getEnd, outliers))

    //Remove old points
    var deletedMCs = mutable.HashSet[Int]()
    elements
      .filter(p => p._2.arrival < window.getStart + slide)
      .foreach(p => {
        val delete = deletePoint(p._2)
        if (delete > 0) deletedMCs += delete
      })

    //Delete MCs
    if (deletedMCs.nonEmpty) {
      var reinsert = ListBuffer[Data]()
      deletedMCs.foreach(mc => {
        reinsert = reinsert ++ state.value().MC(mc).points
        state.value().MC.remove(mc)
      })
      val reinsertIndexes = reinsert.map(_.id)

      //Reinsert points from deleted MCs
      reinsert.foreach(p => insertPoint(p, false, reinsertIndexes))
    }
  }

  def insertPoint(el: Data, newPoint: Boolean, reinsert: ListBuffer[Int] = null): Unit = {
    var state = this.state.value()
    if (!newPoint) el.clear(-1)
    //Check against MCs on 3/2R
    val closeMCs = findCloseMCs(el)
    //Check if closer MC is within R/2
    val closerMC = if (closeMCs.nonEmpty)
      closeMCs.minBy(_._2)
    else
      (0, Double.MaxValue)
    if (closerMC._2 <= R / 2) { //Insert element to MC
      if (newPoint) {
        insertToMC(el, closerMC._1, true)
      }
      else {
        insertToMC(el, closerMC._1, false, reinsert)
      }
    }
    else { //Check against PD
      val NC = ListBuffer[Data]()
      val NNC = ListBuffer[Data]()
      state.PD.values
        .foreach(p => {
          val thisDistance = distance(el, p)
          if (thisDistance <= 3 * R / 2) {
            if (thisDistance <= R) { //Update metadata
              addNeighbor(el, p)
              if (newPoint) {
                addNeighbor(p, el)
              }
              else {
                if (reinsert.contains(p.id)) {
                  addNeighbor(p, el)
                }
              }
            }
            if (thisDistance <= R / 2) NC += p
            else NNC += p
          }
        })

      if (NC.size >= k) { //Create new MC
        createMC(el, NC, NNC)
      }
      else { //Insert in PD
        closeMCs.foreach(mc => el.Rmc += mc._1)
        state.MC.filter(mc => closeMCs.contains(mc._1))
          .foreach(mc => {
            mc._2.points.foreach(p => {
              val thisDistance = distance(el, p)
              if (thisDistance <= R) {
                addNeighbor(el, p)
              }
            })
          })
        state.PD += ((el.id, el))
      }
    }
  }

  def deletePoint(el: Data): Int = {
    var res = 0
    if (el.mc <= 0) { //Delete it from PD
      state.value().PD.remove(el.id)
    } else {
      state.value().MC(el.mc).points -= el
      if (state.value().MC(el.mc).points.size <= k) res = el.mc
    }
    res
  }

  def createMC(el: Data, NC: ListBuffer[Data], NNC: ListBuffer[Data]): Unit = {
    NC.foreach(p => {
      p.clear(mc_counter)
      state.value().PD.remove(p.id)
    })
    el.clear(mc_counter)
    NC += el
    val newMC = new MicroCluster(el.value, NC)
    state.value().MC += ((mc_counter, newMC))
    NNC.foreach(p => p.Rmc += mc_counter)
    mc_counter += 1
  }

  def insertToMC(el: Data, mc: Int, update: Boolean, reinsert: ListBuffer[Int] = null): Unit = {
    el.clear(mc)
    state.value().MC(mc).points += el
    if (update) {
      state.value.PD.values.filter(p => p.Rmc.contains(mc)).foreach(p => {
        if (distance(p, el) <= R) {
          addNeighbor(p, el)
        }
      })
    }
    else {
      state.value.PD.values.filter(p => p.Rmc.contains(mc) && reinsert.contains(p.id)).foreach(p => {
        if (distance(p, el) <= R) {
          addNeighbor(p, el)
        }
      })
    }
  }

  def findCloseMCs(el: Data): mutable.HashMap[Int, Double] = {
    val res = mutable.HashMap[Int, Double]()
    state.value().MC.foreach(mc => {
      val thisDistance = distance(el, mc._2)
      if (thisDistance <= (3 * R) / 2) res.+=((mc._1, thisDistance))
    })
    res
  }

  def addNeighbor(el: Data, neigh: Data): Unit = {
    if (el.arrival > neigh.arrival) {
      el.insert_nn_before(neigh.arrival, k)
    } else {
      el.count_after += 1
      if (el.count_after >= k) el.safe_inlier = true
    }
  }

}
