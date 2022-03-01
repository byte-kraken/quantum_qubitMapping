import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*

const val INPUT_PATH = "benchmarks/original"
const val OUTPUT_PATH = "benchmarks/remapped"
const val DEBUG = false

var sumSwapsAllFiles = 0

/***
 * Every file is parsed twice:
 * On the first parse, all nodes are extracted and an initial mapping is generated.
 * On the second parse, the newly mapped qubits are replaced, swapping is performed and the resulting code is written into the output file
 */
fun main() {
    val paths = Files.walk(Paths.get(INPUT_PATH))

    // TODO: remove
    /*val fn = "adder-3.qasm"
    parseAndMapLogicalQubits(Paths.get(INPUT_PATH, fn), Paths.get(OUTPUT_PATH, fn))*/
     paths.filter { Files.isRegularFile(it) }.forEach {
         parseAndMapLogicalQubits(it, Paths.get(OUTPUT_PATH, it.fileName.toString()))
     }
    println("\nSum swaps all files: $sumSwapsAllFiles")
}

data class LogicalReg(
    val qubits: MutableMap<String, LogicalQubit>,
    val measurements: MutableMap<String, MeasurementBit>
) {
    var initialRegs: MutableList<Pair<String, Int>> = mutableListOf()
    lateinit var physicalReg: PhysicalReg

    fun addC(name: String): MeasurementBit {
        return measurements.getOrPut(name) { MeasurementBit(name) }
    }

    fun addQ(qubitString: String): LogicalQubit {
        return qubits.getOrPut(qubitString) { LogicalQubit(qubitString) }
    }

    fun printQReg() {
        qubits.filterValues { it.physicalQubit != null }.values.forEach {
            println("${it.name} => ${it.physicalQubit?.id ?: "not set"}")
        }
    }

    fun getPhysical(logicalName: String): String =
        qubits[logicalName]?.physicalQubit.toString()

    fun getPhysical(logicalQubit: LogicalQubit): String =
        logicalQubit.physicalQubit.toString()

    /**
     * Move one node to the other to make them neighbors.
     * This algorithm is very crude and can be massively improved.
     * E.g.: Try moving each by one step and see which ones connected-score drops less.
     */
    fun makeNeighbors(lq1: LogicalQubit, lq2: LogicalQubit): List<PhysicalQubit> {
        if (lq1.physicalQubit == null || lq2.physicalQubit == null) error("Logical qubits have not been assigned")

        // already neighbors
        if (lq1.physicalQubit!!.neighbors.contains(lq2.physicalQubit)) return emptyList()

        // get path between nodes
        val (source, target) = moveBasedOnLRU(lq1, lq2)
        val path = source.physicalQubit!!.getShortestPathBFS(target.physicalQubit!!).toMutableList()

        moveSourceToTarget(path)

        // TODO: for iterative moving approach: CHANGE METHOD, RETURNING PATH NO LONGER VALID. MOVE PRINTING FROM PARSER HERE INSTEAD.
        //val path = moveBasedOnConnectednessEvenMoreComplex(lq1, lq2)

        return path
    }

    private fun moveSourceToTarget(path: MutableList<PhysicalQubit>) {
        path.removeAt(path.lastIndex) // remove target from necessary swaps (neighbors don't need to be swapped)

        var swapper1 = path[0]
        path.forEachIndexed { index, swapper2 ->
            if (index != 0) { // skip source (defined before loop)
                swap(swapper1, swapper2)
                swapper1 = swapper2
            }
        }
    }

    /**
     * Swaps two logical qubits by reassigning their physical qubit pointers.
     */
    private fun swap(pq1: PhysicalQubit?, pq2: PhysicalQubit?) {
        val logicalOfSwapper1 = qubits.values.firstOrNull {
            it.physicalQubit == pq1
        }
        val logicalOfSwapper2 = qubits.values.firstOrNull {
            it.physicalQubit == pq2
        }
        /*logicalOfSwapper1?.physicalQubit = pq2
        logicalOfSwapper2?.physicalQubit = pq1*/

        logicalOfSwapper1?.let {
            qubits[it.name] = LogicalQubit(it.name)
            qubits[it.name]?.physicalQubit = pq2
        }
        logicalOfSwapper2?.let {
            qubits[it.name] = LogicalQubit(it.name)
            qubits[it.name]?.physicalQubit = pq1
        }

    }

    /**
     * Find out which node should move:
     * Using a systems similar to LRU, try to move recently used qubits to center.
     */
    private fun moveBasedOnLRU(lq1: LogicalQubit, lq2: LogicalQubit): Pair<LogicalQubit, LogicalQubit> {
        val distanceToCenter1 = lq1.physicalQubit!!.getShortestPathBFS(physicalReg.center!!).size
        val distanceToCenter2 = lq2.physicalQubit!!.getShortestPathBFS(physicalReg.center!!).size

        // higher distance should be moving (= is source)
        return if (distanceToCenter1 >= distanceToCenter2) {
            lq1 to lq2
        } else {
            lq2 to lq1
        }

    }

    /**
     * Find out which node should move:
     * Move the less connected node to the more connected one.
     */
    private fun moveBasedOnConnectednessSimple(lq1: LogicalQubit, lq2: LogicalQubit): Pair<LogicalQubit, LogicalQubit> {
        val lq1Connectedness = lq1.edges.sumOf { it.weight }
        val lq2Connectedness = lq2.edges.sumOf { it.weight }

        return if (lq2Connectedness >= lq1Connectedness) {
            lq2 to lq1
        } else {
            lq1 to lq2
        }
    }

    /**
     * Find out which node should move:
     * Move the less connected node to the more connected one:
     * Look at where the node would end up after moving and compare the change in edge value.
     * Optimize for the highest edge value.
     */
    private fun moveBasedOnConnectednessComplex(
        lq1: LogicalQubit,
        lq2: LogicalQubit
    ): Pair<LogicalQubit, LogicalQubit> {
        val lq1ToLq2 = lq1.physicalQubit!!.getShortestPathBFS(lq2.physicalQubit!!).toMutableList()
        val lq2ToLq1 = lq1ToLq2.reversed().toMutableList()

        val testRegLq1 = this.copy(qubits = qubits.toMutableMap())
        testRegLq1.moveSourceToTarget(lq1ToLq2)
        val currentEdgeScoreTotalLq1 = testRegLq1.qubits.values.sumOf { it.calculateCurrentEdgeScore() }

        val testRegLq2 = this.copy(qubits = qubits.toMutableMap())
        testRegLq2.moveSourceToTarget(lq2ToLq1)
        val currentEdgeScoreTotalLq2 = testRegLq2.qubits.values.sumOf { it.calculateCurrentEdgeScore() }

        return if (currentEdgeScoreTotalLq1 > currentEdgeScoreTotalLq2) {
            lq1 to lq2
        } else {
            lq2 to lq1
        }

        /*
        val lq1CurrentEdgeScore = lq1.calculateCurrentEdgeScore()
        val lq2CurrentEdgeScore = lq2.calculateCurrentEdgeScore()
        val currentEdgeScoreTotal = qubits.values.sumOf { it.calculateCurrentEdgeScore() }
        val lq1ScoreChange = lq1CurrentEdgeScore - testRegLq1.qubits[lq1.name]!!.calculateCurrentEdgeScore()
        val lq2ScoreChange = lq2CurrentEdgeScore - testRegLq2.qubits[lq2.name]!!.calculateCurrentEdgeScore()

        return if (lq1ScoreChange > lq2ScoreChange) {
            lq1 to lq2
        } else {
            lq2 to lq1
        }
        */
    }


    /**
     * Find out which node should move:
     * Move the less connected node to the more connected one:
     * Look at where the node would end up after moving and compare the change in edge value.
     * Optimize for the highest edge value.
     */
    private fun moveBasedOnConnectednessEvenMoreComplex(
        lq1: LogicalQubit,
        lq2: LogicalQubit
    ): MutableList<PhysicalQubit> {
        val path = lq1.physicalQubit!!.getShortestPathBFS(lq2.physicalQubit!!).toMutableList()
        path.removeAt(path.lastIndex) // remove last swap (already at end)

        // TODO: try all possible positions on the path and compare score,
        //  e.g.: lq1 moves x steps, lq2 pathlength - x steps from the other side
        var i = 0
        var bestValue = Double.MIN_VALUE
        var bestIndex = 0
        while (i < path.size) {
            val testReg = this.copy(qubits = qubits.toMutableMap())
            testReg.moveToIndexInPath(path, i)
            val currentEdgeScoreTotal = testReg.qubits.values.sumOf { it.calculateCurrentEdgeScore() }
            if (currentEdgeScoreTotal > bestValue) {
                bestValue = currentEdgeScoreTotal
                bestIndex = i
            }
            i++
        }
        moveToIndexInPath(path, bestIndex)
        return path
    }

    /**
     * Split the path at an index, move the source and target there.
     */
    fun moveToIndexInPath(path: MutableList<PhysicalQubit>, index: Int) {
        var j = 1
        var frontSwapper = path[0]
        var backSwapper = path[path.lastIndex]
        while (j <= path.lastIndex) {
            var currSwapper = path[j]
            if (j < index) {
                // swap source towards target
                swap(frontSwapper, currSwapper)
                frontSwapper = currSwapper
            } else {
                // swap target towards source
                currSwapper = path[path.lastIndex - (j - index)]
                swap(backSwapper, currSwapper)
                backSwapper = currSwapper
            }
            j++
        }
    }
}

data class LogicalQubit(
    val name: String,
) {
    var edges: MutableSet<Edge> = mutableSetOf()
    var numTimesUsedInEdge: Int = 0
    var physicalQubit: PhysicalQubit? = null

    fun addOrUpdateEdge(node: LogicalQubit) {
        getEdge(node)?.updateWeight() ?: edges.add(Edge(Pair(this, node)).also { node.edges.add(it) })
    }

    fun getEdge(node: LogicalQubit): Edge? {
        return edges.firstOrNull { it.nodes.first == node || it.nodes.second == node }
    }

    fun mapToPhysical(physicalQubit: PhysicalQubit) {
        this.physicalQubit = physicalQubit
        physicalQubit.used = true
    }

    fun getTightestNode(): LogicalQubit? {
        return edges.maxByOrNull { it.weight }?.getOther(this)
    }

    fun isNeighbor(logicalQubit: LogicalQubit): Boolean {
        return physicalQubit?.neighbors?.contains(logicalQubit.physicalQubit) ?: return false
    }

    /**
     * See how well physical location of the qubits is currently suited
     */
    fun calculateCurrentEdgeScore(): Double {
        return edges.filter {
            this.isNeighbor(it.getOther(this))
        }.sumOf { it.weight }
    }
}

data class Edge(
    val nodes: Pair<LogicalQubit, LogicalQubit>
) {
    var weight: Double = 2.0
    private var prevNodesUsage: Int = nodes.first.numTimesUsedInEdge + nodes.second.numTimesUsedInEdge

    /**
     * Update weight based on the amount of times the nodes in this edge were used in other edges.
     * The goal is to increase the impact of frequent interaction in a short timespan.
     */
    fun updateWeight() {
        weight += defaultWeightIncrease / (1.0 + (sumNodesUsage() - prevNodesUsage))

        nodes.first.numTimesUsedInEdge++
        nodes.second.numTimesUsedInEdge++

        prevNodesUsage = sumNodesUsage()
    }

    private fun sumNodesUsage() = (nodes.first.numTimesUsedInEdge + nodes.second.numTimesUsedInEdge)

    fun getOther(here: LogicalQubit) =
        if (nodes.first == here) nodes.second
        else if (nodes.second == here) nodes.first
        else {
            error("Edge does not contain $here.")
        }

    companion object {
        const val defaultWeightIncrease: Int = 1
    }
}

data class PhysicalReg(val qubits: Set<PhysicalQubit>) {
    var center: PhysicalQubit? = null
        get() {
            return if (field != null) field
            else {
                qubits.forEach {
                    it.connectedScore = findCenterRec(it, qubits.size)
                }

                qubits.maxByOrNull {
                    it.connectedScore
                }
            }
        }

    /**
     * places a logical node arbitrarily on physical reg, but neighboring a used node
     */
    fun placeArbitrarily(qubit: LogicalQubit) {
        // all qubits occupied
        if (qubits.all { it.used }) {
            throw RuntimeException("Too many logical qubits for given physical Reg.")
        }

        qubits.forEach {
            if (!it.used && it.neighbors.isNotEmpty()) {
                it.neighbors.forEach { neighbour ->
                    if (neighbour.used) {
                        qubit.mapToPhysical(it)
                        return
                    }
                }
            }
        }

        // no qubit placed yet
        qubit.physicalQubit = qubits.first().also { it.used = true }
    }

    /**
     * Tries to find the most connected starting position by inspecting the whole reg.
     * Recursively adds up the number of neighbors of all nodes to find the center.
     * Greedily tries to find a location near the center that has the most connections.
     * @param numQubits the number of qubits that need to be placed. Changes the greedy-function.
     */
    fun findOptimalStartingPos(numQubits: Int): PhysicalQubit {
        // maybe use priority queue instead and rank all neighbors for all nodes based on connectivity
        center ?: error("No physical qubits found.")

        var currBest = findCenterRec(center!!, numQubits)
        var gradientScore = Integer.MAX_VALUE
        var bestStart = center!!

        while (gradientScore > currBest) {
            bestStart.neighbors.forEach {
                gradientScore = findCenterRec(it, numQubits)
                if (gradientScore > currBest) {
                    currBest = gradientScore
                    bestStart = it
                }
            }
        }

        return bestStart
    }

    private fun findCenterRec(
        source: PhysicalQubit,
        levelsLeft: Int,
        visited: MutableSet<PhysicalQubit> = mutableSetOf(),
        ledge: MutableList<PhysicalQubit> = mutableListOf(),
        uniqueNeighbors: MutableSet<PhysicalQubit> = mutableSetOf()
    ): Int {
        visited.add(source)

        if (levelsLeft <= 0) {
            return uniqueNeighbors.size
        }
        uniqueNeighbors.addAll(source.neighbors)

        ledge.addAll(source.neighbors.filter { !visited.contains(it) })

        val nextQubit = ledge.removeFirstOrNull() ?: return uniqueNeighbors.size

        return findCenterRec(nextQubit, levelsLeft - 1, visited, ledge, uniqueNeighbors)
    }

    companion object {
        const val QREG_NAME: String = "Q"

        fun getDemoReg(): PhysicalReg {
            val arr: MutableList<PhysicalQubit> = mutableListOf()
            for (i in 0..64) {
                arr.add(PhysicalQubit(i))
            }
            // setting neighbors
            for (i in 1..8) {
                arr[i].addNeighborBidirectional(arr[i - 1])
                arr[i].addNeighborBidirectional(arr[i + 1])
            }
            arr[0].addNeighborBidirectional(arr[10])
            arr[10].addNeighborBidirectional(arr[13])
            arr[4].addNeighborBidirectional(arr[11])
            arr[11].addNeighborBidirectional(arr[17])
            arr[8].addNeighborBidirectional(arr[12])
            arr[12].addNeighborBidirectional(arr[21])

            for (i in 14..22) {
                arr[i].addNeighborBidirectional(arr[i - 1])
                arr[i].addNeighborBidirectional(arr[i + 1])
            }
            arr[15].addNeighborBidirectional(arr[24])
            arr[24].addNeighborBidirectional(arr[29])
            arr[19].addNeighborBidirectional(arr[25])
            arr[25].addNeighborBidirectional(arr[33])
            arr[23].addNeighborBidirectional(arr[26])
            arr[26].addNeighborBidirectional(arr[37])

            for (i in 28..36) {
                arr[i].addNeighborBidirectional(arr[i - 1])
                arr[i].addNeighborBidirectional(arr[i + 1])
            }
            arr[27].addNeighborBidirectional(arr[38])
            arr[38].addNeighborBidirectional(arr[41])
            arr[31].addNeighborBidirectional(arr[39])
            arr[39].addNeighborBidirectional(arr[45])
            arr[35].addNeighborBidirectional(arr[40])
            arr[40].addNeighborBidirectional(arr[49])

            for (i in 42..50) {
                arr[i].addNeighborBidirectional(arr[i - 1])
                arr[i].addNeighborBidirectional(arr[i + 1])
            }
            arr[43].addNeighborBidirectional(arr[52])
            arr[52].addNeighborBidirectional(arr[56])
            arr[47].addNeighborBidirectional(arr[53])
            arr[53].addNeighborBidirectional(arr[60])
            arr[51].addNeighborBidirectional(arr[54])
            arr[54].addNeighborBidirectional(arr[64])

            for (i in 56..63) {
                arr[i].addNeighborBidirectional(arr[i - 1])
                arr[i].addNeighborBidirectional(arr[i + 1])
            }
            return PhysicalReg(arr.toSet())
        }

    }
}

data class PhysicalQubit(
    val id: Int,
) {
    val neighbors: MutableSet<PhysicalQubit> = mutableSetOf()
    var used: Boolean = false
    var connectedScore = 0

    fun addNeighborBidirectional(neighbor: PhysicalQubit) {
        neighbors.add(neighbor)
        neighbor.neighbors.add(this)
    }

    override fun toString(): String {
        return "${PhysicalReg.QREG_NAME}[$id]"
    }

    /**
     * Uses BFS recursively to find the closest free physical qubit by traversing neighbors
     */
    fun getClosestFree(): PhysicalQubit = findClosestBFSRec(this)

    private fun findClosestBFSRec(
        source: PhysicalQubit,
        visited: MutableSet<PhysicalQubit> = mutableSetOf(),
        ledge: MutableList<PhysicalQubit> = mutableListOf(),
    ): PhysicalQubit {
        if (!source.used) {
            return source
        }

        visited.add(source)
        ledge.addAll(source.neighbors.filter { !visited.contains(it) })
        val nextQubit = ledge.removeFirstOrNull() ?: error("no more free physical qubits")

        return findClosestBFSRec(nextQubit, visited, ledge)
    }


    /***
     * Uses adapted BFS with path storage to calculate shortest path.
     * @param this the source and start of the path
     * @param target the end of the path
     * @return the path from source to target, including both
     */
    fun getShortestPathBFS(target: PhysicalQubit): List<PhysicalQubit> {
        val paths = mutableMapOf<PhysicalQubit, MutableList<PhysicalQubit>>()
        val visited = mutableSetOf<PhysicalQubit>()
        val ledge = mutableListOf<PhysicalQubit>()

        ledge.add(this)
        paths[this] = mutableListOf<PhysicalQubit>().also { it.add(this) }

        while (true) {
            val curr = ledge.removeAt(ledge.lastIndex)

            val pathUpTillCurr = paths[curr]!!
            if (curr == target) return pathUpTillCurr

            visited.add(curr)

            curr.neighbors.filter { !visited.contains(it) }.forEach {
                ledge.add(0, it)
                val newPath = pathUpTillCurr.toMutableList()
                newPath.add(it)
                paths[it] = newPath
            }
        }
    }
}

data class MeasurementBit(val name: String) {
    override fun toString(): String {
        return name
    }
}

/**
 * Performs two parses of the input file.
 *
 * On the first parse, all logical qubits are extracted and mapped to physical qubits.
 *
 * On the second parse, the logical qubits are exchanged for their physical ones, and swaps are performed.
 */
fun parseAndMapLogicalQubits(inputFile: Path, outputFile: Path) {
    println("\n${inputFile.fileName}")
    val logicalReg = LogicalReg(mutableMapOf(), mutableMapOf())
    val physicalReg = PhysicalReg.getDemoReg()
    logicalReg.physicalReg = physicalReg
    val mappingParser = MappingParser(Scanner(FileInputStream(File(inputFile.toUri()))), logicalReg, DEBUG)
    mappingParser.Parse()

    if (DEBUG) logicalReg.qubits.flatMap { it.value.edges }.distinct().sortedBy { it.weight }
        .forEach { println("${it.weight}: ${it.nodes.first.name} - ${it.nodes.second.name}") }

    mapQubits(logicalReg, physicalReg)

    if (DEBUG) logicalReg.printQReg()

    val swappingParser =
        SwappingParser(
            Scanner(FileInputStream(File(inputFile.toUri()))),
            File(outputFile.toUri()),
            logicalReg,
            physicalReg,
            DEBUG
        )
    swappingParser.Parse()
    println(
        "Total Cost: ${swappingParser.cost}\n" +
                "Num logical qubits: ${logicalReg.qubits.size}\n" +
                "Num CX operations: ${swappingParser.numCX}\n" +
                "Inserted swaps: ${swappingParser.numSwaps}"
    )
    debug("Costs without swaps: ${swappingParser.cost - swappingParser.numSwaps * swappingParser.SWAP_COST}")

    sumSwapsAllFiles += swappingParser.numSwaps
}

/**
 * @param node the node that needs to be mapped
 * @param source the already mapped neighbor that caused this node to become an entry
 * @param weight the weight of their connecting edge
 */
data class MapEntry(val source: LogicalQubit, val node: LogicalQubit, val weight: Double)

/**
 * Maps all logical qubits to physical qubits.
 * First, the logical qubit with the most edges is mapped to the best physical starting position.
 * Then, its neighbors and their neighbors are recursively mapped nearby, depending on the weight of their connection.
 * Lastly, all logical qubits without edges are placed arbitrarily (but next to an already occupied physical qubit)
 */
fun mapQubits(logicalReg: LogicalReg, physicalReg: PhysicalReg) {
    // ## qubits with edges ##
    val qubitsWithEdges = logicalReg.qubits.values.filter { it.edges.isNotEmpty() }
    val bestConnectedLogicalQubit =
        qubitsWithEdges.maxByOrNull { it.edges.sumOf { edge -> edge.weight } } ?: error("no logical qubits found")
    val optimalPhysicalStartingQubit = physicalReg.findOptimalStartingPos(qubitsWithEdges.size)
    bestConnectedLogicalQubit.mapToPhysical(optimalPhysicalStartingQubit)

    // init ledge
    val ledge: PriorityQueue<MapEntry> = PriorityQueue { t1, t2 -> t2.weight.compareTo(t1.weight) }
    bestConnectedLogicalQubit.edges.forEach {
        ledge.add(MapEntry(bestConnectedLogicalQubit, it.getOther(bestConnectedLogicalQubit), it.weight))
    }

    // map
    while (ledge.isNotEmpty()) {
        val nextMapEntry = ledge.poll()
        if (nextMapEntry.node.physicalQubit != null) continue

        nextMapEntry.node.mapToPhysical(nextMapEntry.source.physicalQubit!!.getClosestFree())

        nextMapEntry.node.edges.forEach {
            val otherEnd = it.getOther(nextMapEntry.node)
            ledge.add(MapEntry(nextMapEntry.node, otherEnd, it.weight))
        }
    }

    // ## qubits without edges ##
    logicalReg.qubits.values.filter { it.physicalQubit == null }.forEach { physicalReg.placeArbitrarily(it) }
}

fun debug(message: String) {
    if (DEBUG) println(message)
}


