import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ionspin.kotlin.bignum.decimal.toBigDecimal
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import responses_approved.options.OptionOrder
import responses_approved.options.enums.Side
import responses_approved.options.enums.OrderState
import java.io.File
import kotlinx.datetime.*
import responses_approved.crypto.CryptoOrder
import responses_approved.general.Page

import kotlin.math.abs

fun main() {
    val rh = RHRepository()

    val cryptoOrders = runBlocking {
        loopPagenated(rh,method = RHRepository::getCryptoOrders,null)
    }

    println(cryptoOrders.size)
//    listOf(CryptoOrder::state,CryptoOrder::side,CryptoOrder::timeInForce,CryptoOrder::type,CryptoOrder::currencyPairId)
//            .forEach{ println(it.name+getDistinct(cryptoOrders,it)) }


//    analyzeOptions(rh,false)

}

suspend fun <T> loopPagenated(rh: RHRepository, method: suspend RHRepository.(c: String?) -> Page<T>,cursor: String?): List<T>{
    val p = rh.method(cursor)
    return p.next?.let {
        p.results.plus(loopPagenated(rh,method,it.split("=")[1]))
    } ?: p.results
}

fun <T> getDistinct(data: List<T>, method: T.() -> Any?): List<Any?> =
    data.distinctBy { it.method() }.map { it.method() }

fun totalAmount(legs: List<OptionOrder.LegA>?): BigDecimal {
//    return legs?.let{ i ->
//        i.flatMap { it.executions }.sumOf {
//            println("${it.price}x${it.quantity}")
//            it.quantity*it.price
//        }
//    } ?: 0.0
    return legs?.flatMap { it.executions }
        ?.fold(BigDecimal.ZERO) { acc, e ->
            println("${e.price}x${e.quantity}")
            acc + e.quantity.toBigDecimal()*e.price.toBigDecimal()
        } ?: BigDecimal.ZERO
}

fun idFromURL(url: String): String = url.dropLast(1).split("/").last()

fun calcProfits(
    rh: RHRepository,
    optionOrdersMap: Map<String, Map<Side, List<OptionOrder.LegA>>>,
): List<Pair<String, Int>> {

    val optionInstruments = optionOrdersMap.keys.chunked(64).map {
        runBlocking { rh.getOptionInstruments(it) }.results
    }.flatten()

    val instrumentToLegs = optionInstruments.map { it to optionOrdersMap.getValue(it.id) }

    return instrumentToLegs.map { (option,legs) ->
        val summary = with(option){
            "$chainSymbol $strikePrice $type $expirationDate"
        }
        println(summary)

        println("Buys")
        val buysTotal = totalAmount(legs[Side.BUY])
        println("Total: ${buysTotal.toStringExpanded()}")

        println("Sells")
        val sellsTotal = totalAmount(legs[Side.SELL])
        println("Total: ${sellsTotal.toStringExpanded()}")

        val profit = ((sellsTotal-buysTotal)*100.0.toBigDecimal()).toBigInteger().intValue()
//        println(((sellsTotal-buysTotal)*100.bn).toBigInt().toInt())
        println("Profit: $profit")

        summary to profit
    }.sortedBy { -it.second }
}

fun analyzeOptions(rh: RHRepository, writeToFile: Boolean = true){
    val optionOrders = runBlocking { rh.getOptionOrders() }.results
        .filter { it.state==OrderState.FILLED }
//        .filter { it.chainSymbol!="JG" }

    println("Money made from beyond limit orders: "
            +optionOrders.sumOf { abs(it.legs[0].executions[0].price - it.price) }*100)

    println(optionOrders.flatMap { it.legs }.size)

    optionOrders.forEachIndexed { i, v ->
        if(i==100)
            println(Json.encodeToString(v))
    }

//    Used to Check Enum States
//    listOf(OptionOrder::state,OptionOrder::timeInForce,OptionOrder::trigger,OptionOrder::type,OptionOrder::timeInForce,
//        OptionOrder::responseCategory, OptionOrder::openingStrategy,OptionOrder::closingStrategy,OptionOrder::formSource)
//        .forEach{ println(it.name+getDistinct(optionOrders,it)) }
//
//    listOf(OptionOrder.LegA::positionEffect,OptionOrder.LegA::side,OptionOrder.LegA::optionType)
//        .forEach { i -> println(i.name+getDistinct(optionOrders.flatMap { it.legs },i)) }

    val optionPositions = runBlocking { rh.getOptionAggregatePosition() }.results
        .flatMap { it.legs }.map { idFromURL(it.option) }

    println(optionPositions[0])
    val optionOrdersMap = optionOrders.flatMap { it.legs }.groupBy { idFromURL(it.option) }
        .filterKeys { !optionPositions.contains(it) }//remove active positions from calculation
        .mapValues { i -> i.value.groupBy { it.side } }//separate legs into map by sides

    println(optionOrdersMap.values.sumOf { (it[Side.BUY]?.size ?: 0)+ (it[Side.SELL]?.size ?: 0)})

    val optionProfits = calcProfits(rh,optionOrdersMap)

    if(writeToFile) {
        File("optionProfits.txt").writeText(
            "Stock,Strike Price,Type,Expiration Date,Profit\n" +
                    optionProfits.joinToString("\n") { it.first.replace(" ", ",") + "," + it.second }
        )
        File("optionProfits2.txt").writeText(
            optionProfits.joinToString("\n") { it.first + "," + it.second }
        )
    }

    println(optionProfits)
    println("Total Profit:"+optionProfits.sumOf { it.second })
    println(optionProfits.size)
    println("Negative: ${optionProfits.count { it.second < 0 }} " +
            "Positive: ${optionProfits.count { it.second > 0 }}")

    val companyProfits = optionProfits
        .groupBy { it.first.split(" ")[0] }
        .map { (key, value) -> key to value.sumOf { it.second } }.sortedBy { -it.second }

    if(writeToFile)
        File("companyProfits.txt").writeText(
            companyProfits.joinToString("\n"){ it.first+","+it.second }
        )

    println(companyProfits)
    println(companyProfits.size)
    println("Lost total: ${companyProfits.count { it.second < 0 }} " +
            "Gained: ${companyProfits.count { it.second > 0 }}")
}