package dev.example.graph

import scala.annotation.tailrec

/** A commit graph's lanes. */
final case class Commit(hash: String, parents: List[String])

object Lanes:
  val MaxLanes: Int = 64

  def assign(commits: Seq[Commit]): Map[String, Int] =
    @tailrec
    def loop(rest: List[Commit], lanes: Vector[String], acc: Map[String, Int]): Map[String, Int] =
      rest match
        case Nil => acc
        case c :: tail =>
          val lane = lanes.indexOf(c.hash) match
            case -1 => lanes.size
            case i  => i
          loop(tail, lanes.updated(lane min (lanes.size - 1), c.hash), acc + (c.hash -> lane))
    loop(commits.toList, Vector.empty, Map.empty) // start empty

  def describe(c: Commit): String = s"${c.hash.take(7)} with ${c.parents.length} parents"
