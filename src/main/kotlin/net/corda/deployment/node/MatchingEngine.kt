package net.corda.deployment.node

import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.Comparator

enum class OrderType {
    LIMIT,
    MARKET
}

enum class OrderSide {
    BUY,
    SELL
}

data class Order(
    val orderType: OrderType,
    val orderSide: OrderSide,
    val price: Long,
    val quantity: Long,
    val participant: String,
    val timestamp: Long
)

data class Trade(val price: Long, val quantity: Long, val sellParticipant: String, val buyParticipant: String, val timestamp: Long)

class OrderBook {
    val comparator: Comparator<Order> = Comparator.comparingLong<Order> { o -> o.price }.thenComparingLong { o -> o.timestamp }

    //bids have highest first (highest price willing to buy)
    //asks have lowest first (lowest price willing to sell)
    val bids = PriorityQueue<Order>(comparator.reversed())
    val asks = PriorityQueue<Order>(comparator)

    fun addOrder(order: Order) {
        when (order.orderSide) {
            OrderSide.BUY -> {
                bids.add(order)
            }
            OrderSide.SELL -> {
                asks.add(order)
            }
        }
    }
}

class MatchingEngine(
    private val orderQueue: ArrayBlockingQueue<Order>,
    private val tradeQueue: ArrayBlockingQueue<Trade>
) {
    val orderBook: OrderBook = OrderBook()
    val match: AtomicBoolean = AtomicBoolean(true)

    fun run() {
        val incommingOrder = orderQueue.take()
        // we have a match
        when (incommingOrder.orderSide) {
            OrderSide.BUY -> {
                //buyside is the aggressor
                if (orderBook.asks.peek() != null && orderBook.asks.peek().price <= incommingOrder.price) {
                    //we have a match (tip of asks is less than or equal to tip of bids)
                    val consumedAsks = LinkedList<Order>()
                    var quantityLeft = incommingOrder.quantity
                    //while there is a valid ask to consume, and the price is less or equal to our order, and the order still has some quantity
                    while (orderBook.asks.peek() != null && (orderBook.asks.peek().price <= incommingOrder.price) && quantityLeft > 0) {
                        //remove the tip of asks queue
                        val askToConsume = orderBook.asks.remove()
                        //if ask has more quantity than bid, take bid quantity, if bid has more than ask take ask
                        val quantityToConsume = quantityLeft.coerceAtMost(askToConsume.quantity)
                        if ((askToConsume.quantity - quantityToConsume) == 0L) {
                            //this ask has been fully consumed
                            consumedAsks.add(askToConsume)
                            quantityLeft -= quantityToConsume
                        } else {
                            //there is some left over for this ask - add it back to the queue
                            val quantityLeftOver = askToConsume.quantity - quantityToConsume
                            //amount of ask that was consumed
                            val consumedAsk = askToConsume.copy(quantity = askToConsume.quantity - quantityLeftOver)
                            //amount of ask left over to add back to the queue
                            val leftOverAsk = askToConsume.copy(quantity = quantityLeftOver)
                            orderBook.addOrder(leftOverAsk)
                            consumedAsks.add(consumedAsk)
                            //if there is some quantity on the ask left, there must be no quantity left on the order
                            quantityLeft = 0
                        }
                    }

                    if (quantityLeft > 0) {
                        //if the order was not fully consumed by available asks, add to the order queue
                        orderBook.addOrder(incommingOrder.copy(quantity = quantityLeft))
                    }

                    consumedAsks.forEach {
                        tradeQueue.add(
                            Trade(
                                //use the consumed ask price, not order price
                                it.price,
                                it.quantity,
                                it.participant,
                                incommingOrder.participant,
                                System.currentTimeMillis()
                            )
                        )
                    }
                } else {
                    //no match possible this cycle, just add to queue
                    orderBook.addOrder(incommingOrder)
                }


            }
            OrderSide.SELL -> {
                //sellside is the aggressor
                if (orderBook.bids.peek() != null && orderBook.bids.peek().price >= incommingOrder.price) {
                    //we have a match (highest buy price is greater than current sell order price)
                    val consumedBids = LinkedList<Order>()
                    var quantityLeft = incommingOrder.quantity
                    while (orderBook.bids.peek() != null && (orderBook.bids.peek().price >= incommingOrder.price) && quantityLeft > 0) {
                        val bidToConsume = orderBook.bids.remove()
                        //if ask has more quantity than bid, take bid quantity, if bid has more than ask take ask
                        val quantityToConsume = quantityLeft.coerceAtMost(bidToConsume.quantity)
                        if ((bidToConsume.quantity - quantityToConsume) == 0L) {
                            //this bid has been fully consumed
                            consumedBids.add(bidToConsume)
                            quantityLeft -= quantityToConsume
                        } else {
                            //there is some left over for this bid - add it back to the queue
                            val quantityLeftOver = bidToConsume.quantity - quantityToConsume
                            //amount of bid that was consumed
                            val consumedBid = bidToConsume.copy(quantity = bidToConsume.quantity - quantityLeftOver)
                            //amount of bid left over to add back to the queue
                            val leftOverBid = bidToConsume.copy(quantity = quantityLeftOver)
                            orderBook.addOrder(leftOverBid)
                            consumedBids.add(consumedBid)
                            //if there is some quantity on the bid left, there must be no quantity left on the order
                            quantityLeft = 0
                        }
                    }
                    if (quantityLeft > 0) {
                        //if the order was not fully consumed by available bids, add to the order queue
                        orderBook.addOrder(incommingOrder.copy(quantity = quantityLeft))
                    }

                    consumedBids.forEach {
                        tradeQueue.add(
                            Trade(
                                incommingOrder.price,
                                it.quantity,
                                incommingOrder.participant,
                                it.participant,
                                System.currentTimeMillis()
                            )
                        )
                    }
                } else {
                    //no match possible this cycle
                    orderBook.addOrder(incommingOrder)
                }


            }
        }
    }
}

fun main() {
    val orderQueue = ArrayBlockingQueue<Order>(100)
    val tradeQueue = ArrayBlockingQueue<Trade>(100)
    val matchingEngine = MatchingEngine(orderQueue = orderQueue, tradeQueue = tradeQueue)

    orderQueue.offer(Order(OrderType.LIMIT, OrderSide.BUY, 100, 1, "stefanoBUY", System.currentTimeMillis()))
    matchingEngine.run()
    orderQueue.offer(Order(OrderType.LIMIT, OrderSide.BUY, 99, 1, "stefanoBUY", System.currentTimeMillis()))
    matchingEngine.run()
    orderQueue.offer(Order(OrderType.LIMIT, OrderSide.SELL, 101, 1, "stefanoSELL", System.currentTimeMillis()))
    matchingEngine.run()
    orderQueue.offer(Order(OrderType.LIMIT, OrderSide.SELL, 102, 1, "stefanoSELL", System.currentTimeMillis()))
    matchingEngine.run()
    orderQueue.offer(Order(OrderType.LIMIT, OrderSide.SELL, 98, 4, "stefanoSELL", System.currentTimeMillis()))

    //b-s: [ 99(1), 100(1) ] <-> [ 98(4), 101(1), 102(1) ]

    matchingEngine.run()
    //b-s: [] <-> [ 98(2), 101(1), 102(1) ]
    require(tradeQueue.size == 2)
    //just put in an offer to SELL for 98, there are two existing BUY orders for more than this (99 and 100), there should be two orders
    val (trade1, trade2) = tradeQueue.pollFor(2)
    println(trade1)
    require(trade1.buyParticipant == "stefanoBUY")
    require(trade1.sellParticipant == "stefanoSELL")
    //important that price is 98 because we offer best price to buyers if seller is aggressor
    require(trade1.price == 98L)
    require(trade1.quantity == 1L)

    println(trade2)
    require(trade1.buyParticipant == "stefanoBUY")
    require(trade1.sellParticipant == "stefanoSELL")
    //important that price is 98 because we offer best price to buyers if seller is aggressor
    require(trade1.price == 98L)
    require(trade1.quantity == 1L)

    orderQueue.offer(Order(OrderType.LIMIT, OrderSide.BUY, 102, 4, "BLL", System.currentTimeMillis()))
    //b-s: [ 102(2) ] <-> [ 98(2), 101(1), 102(1) ]
    matchingEngine.run()
    require(tradeQueue.size == 3)
    val (trade3, trade4, trade5) = tradeQueue.pollFor(3).also { print(it) }

    //important that the best price is always offered to the buyer, we expect prices of 98, 101 and 102
    println(trade3)
    require(trade3.buyParticipant == "BLL")
    require(trade3.sellParticipant == "stefanoSELL")
    require(trade3.price == 98L)
    require(trade3.quantity == 2L)

    require(trade4.buyParticipant == "BLL")
    require(trade4.sellParticipant == "stefanoSELL")
    require(trade4.price == 101L)
    require(trade4.quantity == 1L)

    require(trade5.buyParticipant == "BLL")
    require(trade5.sellParticipant == "stefanoSELL")
    require(trade5.price == 102L)
    require(trade5.quantity == 1L)


}

private fun <E> BlockingQueue<E>.pollFor(i: Int): List<E> {
    val mutableListOf = mutableListOf<E>()
    (0 until i).forEach { _ ->
        mutableListOf.add(this.poll())
    }
    return mutableListOf
}
