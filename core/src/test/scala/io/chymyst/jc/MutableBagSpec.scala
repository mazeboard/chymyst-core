package io.chymyst.jc

import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.time.{Millis, Span}
import io.chymyst.test.Common.elapsedTimeMs

class MutableBagSpec extends FlatSpec with Matchers with TimeLimitedTests {

  val timeLimit = Span(3000, Millis)

  val n = 100000

  behavior of "MolValueMapBag"

  it should "retrieve None from empty bag" in {
    val bag = new MutableMapBag[Int]()

    bag.takeOne shouldEqual None
    bag.takeAny(0) shouldEqual Seq()
    bag.takeAny(10) shouldEqual Seq()
  }

  it should "add one element and get it back" in {
    val bag = new MutableMapBag[Int]()

    bag.add(10)
    bag.takeOne shouldEqual Some(10)
  }

  it should "retrieve elements in hash order" in {
    val bag = new MutableMapBag[Int]()

    bag.add(10)
    bag.add(20)
    bag.add(10)
    bag.takeAny(3) shouldEqual Seq(20, 10, 10)
    bag.getCountMap shouldEqual Map(10 -> 2, 20 -> 1)
  }

  it should "retrieve all elements without repetitions" in {
    val bag = new MutableMapBag[Int]()
    val data = (1 to 10) ++ (1 to 10)
    data.foreach(bag.add)
    bag.allValues.toList shouldEqual (1 to 10).toList
  }

  it should "retrieve all elements with repetitions and with skipping" in {
    val bag = new MutableMapBag[Int]()
    val data = (1 to 10) ++ (1 to 10)
    data.foreach(bag.add)
    val expectedValues = List(3, 4) ++ (5 to 10).flatMap(x ⇒ List(x, x)).toList
    bag.allValuesSkipping(List(1, 1, 2, 2, 3, 4)).toList shouldEqual expectedValues
  }

  it should "quickly add and remove elements with the same value" in {
    val b = new MutableMapBag[Int]()
    val t: Long = elapsedTimeMs {
      (1 to n).foreach { _ => b.add(1) }
      (1 to n).foreach { _ => b.remove(1) }
    }._2
    println(s"MolValueMapBag: add and remove $n identical values takes $t ms")
  }

  it should "quickly add and remove elements with different values" in {
    val b = new MutableMapBag[Int]()
    val t: Long = elapsedTimeMs {
      (1 to n).foreach { i => b.add(i) }
      (1 to n).foreach { i => b.remove(i) }
    }._2
    println(s"MolValueMapBag: add and remove $n different values takes $t ms")
  }

  def checkSkippingForBag(b: MutableBag[Int], n: Int): Unit = {
    (1 to n).foreach { i =>
      b.add(i)
      b.add(i)
    }
    val skipped = b.allValuesSkipping(1 to n)
    skipped.size shouldEqual n
    skipped.sum shouldEqual (1 to n).sum
    ()
  }

  it should "quickly add elements with different values and run the skipping iterator" in {
    val b = new MutableMapBag[Int]()
    val t: Long = elapsedTimeMs {
      checkSkippingForBag(b, n)
    }._2
    println(s"MolValueMapBag: skipping with $n different values takes $t ms")
  }

  behavior of "MolValueQueueBag"

  it should "retrieve None from empty queue bag" in {
    val bag = new MutableQueueBag[Int]()

    bag.takeOne shouldEqual None
    bag.takeAny(0) shouldEqual Seq()
    bag.takeAny(10) shouldEqual Seq()
  }

  it should "add one element from queue bag and get it back" in {
    val bag = new MutableQueueBag[Int]()

    bag.add(10)
    bag.takeOne shouldEqual Some(10)
  }

  it should "retrieve elements in FIFO order" in {
    val bag = new MutableQueueBag[Int]()

    bag.add(10)
    bag.add(20)
    bag.add(10)
    bag.takeAny(3) shouldEqual Seq(10, 20, 10)
    bag.getCountMap shouldEqual Map(10 -> 2, 20 -> 1)
  }

  it should "retrieve all elements with repetitions" in {
    val bag = new MutableQueueBag[Int]()
    val data = (1 to 10) ++ (1 to 10)
    data.foreach(bag.add)
    bag.allValues.toList shouldEqual data.toList
  }

  it should "retrieve all elements with repetitions and with skipping" in {
    val bag = new MutableQueueBag[Int]()
    val data = (1 to 10) ++ (1 to 10)
    data.foreach(bag.add)
    val expectedValues = ((5 to 10) ++ (3 to 10)).toList
    bag.allValuesSkipping(List(1, 1, 2, 2, 3, 4)).toList shouldEqual expectedValues
  }

  it should "quickly add and remove elements with the same value" in {
    val b = new MutableQueueBag[Int]()
    val t: Long = elapsedTimeMs {
      (1 to n).foreach { _ => b.add(1) }
      (1 to n).foreach { _ => b.remove(1) }
    }._2
    println(s"MolValueQueueBag: add and remove $n identical values takes $t ms")
  }

  it should "quickly add and remove elements with different values" in {
    val b = new MutableQueueBag[Int]()
    val t: Long = elapsedTimeMs {
      (1 to n).foreach { i => b.add(i) }
      (1 to n).foreach { i => b.remove(i) }
    }._2
    println(s"MolValueQueueBag: add and remove $n different values takes $t ms")
  }

  it should "quickly add elements with different values and run the skipping iterator" in {
    val b = new MutableQueueBag[Int]()
    val t: Long = elapsedTimeMs {
      checkSkippingForBag(b, n)
    }._2
    println(s"MolValueQueueBag: skipping with $n different values takes $t ms")
  }

  behavior of "mutable multiset"

  it should "create empty bag" in {
    val b = new MutableMultiset[Int]
    b.size shouldEqual 0
    b.isEmpty shouldEqual true
    b.getCount(1) shouldEqual 0
    b.contains(1) shouldEqual false
  }

  it should "add one element to bag" in {
    val b = new MutableMultiset[Int]
    b.add(1)
    b.size shouldEqual 1
    b.isEmpty shouldEqual false
    b.getCount(1) shouldEqual 1
    b.getCount(2) shouldEqual 0
    b.contains(1) shouldEqual true
    b.contains(2) shouldEqual false
  }

  it should "add two elements with the same value, them remove them both" in {
    val b = new MutableMultiset[Int]
    b.add(1)
    b.size shouldEqual 1
    b.add(1)
    b.size shouldEqual 2
    b.getCount(1) shouldEqual 2
    b.getCount(2) shouldEqual 0
    b.contains(1) shouldEqual true

    b.remove(1)
    b.getCount(1) shouldEqual 1
    b.contains(1) shouldEqual true
    b.size shouldEqual 1
    b.isEmpty shouldEqual false

    b.remove(1)
    b.size shouldEqual 0
    b.contains(1) shouldEqual false

    b.remove(1)
    b.size shouldEqual 0
    b.isEmpty shouldEqual true
    b.contains(1) shouldEqual false
  }

  it should "add two elements with different values, remove them one by one" in {
    val b = new MutableMultiset[Int]
    b.add(Seq(1, 2, 1))
    b.size shouldEqual 3
    b.getCount(1) shouldEqual 2
    b.getCount(2) shouldEqual 1

    b.toString shouldEqual "Map(2 -> 1, 1 -> 2)"

    b.remove(1)
    b.getCount(1) shouldEqual 1
    b.size shouldEqual 2
    b.contains(1) shouldEqual true
    b.contains(2) shouldEqual true

    b.toString shouldEqual "Map(2 -> 1, 1 -> 1)"

    b.remove(2)
    b.getCount(1) shouldEqual 1
    b.getCount(2) shouldEqual 0
    b.contains(1) shouldEqual true
    b.contains(2) shouldEqual false
    b.size shouldEqual 1

    b.remove(1)
    b.size shouldEqual 0
    b.getCount(1) shouldEqual 0
    b.getCount(2) shouldEqual 0
    b.contains(1) shouldEqual false
    b.contains(2) shouldEqual false
  }

}
