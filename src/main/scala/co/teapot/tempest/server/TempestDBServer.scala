/*
 * Copyright 2016 Teapot, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this
 * file except in compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package co.teapot.tempest.server

import java.{lang, util}

import co.teapot.tempest._
import co.teapot.tempest.algorithm.MonteCarloPPRTyped
import co.teapot.tempest.graph._
import co.teapot.tempest.typedgraph.{BipartiteTypedGraph, IntNode, TypedGraphUnion}
import co.teapot.tempest.util.{CollectionUtil, ConfigLoader, LogUtil}
import co.teapot.thriftbase.TeapotThriftLauncher
import org.apache.thrift.TProcessor

import scala.collection.JavaConverters._
import scala.collection.mutable

/** Given a graph, this thrift server responds to requests about that graph. */
class TempestDBServer(databaseClient: TempestDatabaseClient, config: TempestDBServerConfig)
    extends TempestGraphServer(databaseClient, config) with TempestDBService.Iface {

  type DegreeFilter = collection.Map[DegreeFilterTypes, Int]
  override def getMultiNodeAttributeAsJSON(nodesJava: util.List[Node], attributeName: String): util.Map[Node, String] = {
    databaseClient.getMultiNodeAttributeAsJSON(nodesJava.asScala, attributeName).asJava
  }

  def setNodeAttribute(node: Node, attributeName: String, attributeValue: String): Unit =
    databaseClient.setNodeAttribute(node, attributeName, attributeValue)

  def nodes(nodeType: String, sqlClause: String): util.List[Node] = {
    val nodeIds = databaseClient.nodeIdsMatchingClause(nodeType, sqlClause)
    (nodeIds map { id => new Node(nodeType, id)}).asJava
  }

  def typedGraph(edgeType: String): BipartiteTypedGraph = {
    val edgeConfig = loadEdgeConfig(edgeType)
    BipartiteTypedGraph(edgeConfig.sourceNodeType, edgeConfig.targetNodeType, graph(edgeType))
  }

  /** Returns the type of node reached after k steps along the given edge type starting with the given
    * initial direction. If the edge type
    * is from sourceNodeType to targetNodeType, and edgeDir is EdgeDirOut, this will return sourceNodeType if k is even,
    * or targetNodeType if
    * k is odd.  The parity is swapped if edgeDir is EdgeDirIn.
    */
  def kStepNodeType(edgeType: String, edgeDir: EdgeDir, k: Int): String = {
    val edgeConfig = loadEdgeConfig(edgeType)
    if ((edgeDir == EdgeDirOut && k % 2 == 0) ||
        (edgeDir == EdgeDirIn  && k % 2 == 1)) {
      edgeConfig.getSourceNodeType
    } else {
      edgeConfig.getTargetNodeType
    }
  }

  def kStepNeighborsFiltered(edgeType: String,
                             source: Node,
                             k: Int,
                             sqlClause: String,
                             edgeDir: EdgeDir,
                             degreeFilter: DegreeFilter,
                             alternating: Boolean): util.List[Node] = {
    val sourceTempestId = databaseClient.nodeToTempestId(source)
    val targetNodeType = kStepNodeType(edgeType, edgeDir, k)
    val effectiveGraph = edgeDir match {
      case EdgeDirOut => graph(edgeType)
      case EdgeDirIn => graph(edgeType).transposeView
    }
    val neighborhood = DirectedGraphAlgorithms.kStepOutNeighbors(effectiveGraph, sourceTempestId, k, alternating).toIntArray
    val resultPreFilter: Seq[Int] = if (sqlClause.isEmpty) {
      neighborhood
    } else {
      if (neighborhood.size < TempestServerConstants.MaxNeighborhoodAttributeQuerySize) {
        databaseClient.tempestIdsMatchingClause(targetNodeType, sqlClause + " AND tempest_id in " + neighborhood.mkString("(", ",", ")"))
      } else {
        val candidates = databaseClient.tempestIdsMatchingClause(targetNodeType, sqlClause)
        candidates filter neighborhood.contains
      }
    }
    val resultTempestIds = resultPreFilter filter { id => satisfiesFilters(edgeType, id, degreeFilter) }
    val resultNodes = databaseClient.tempestIdToNodeMulti(targetNodeType, resultTempestIds)
    resultNodes.asJava
  }

  override def kStepOutNeighborsFiltered(edgeType: String,
                                         source: Node,
                                         k: Int,
                                         sqlClause: String,
                                         filter: java.util.Map[DegreeFilterTypes, Integer],
                                         alternating: Boolean): util.List[Node] =
    kStepNeighborsFiltered(edgeType, source, k, sqlClause, EdgeDirOut,
                                       CollectionUtil.toScala(filter), alternating)


  override def kStepInNeighborsFiltered(edgeType: String,
                                        source: Node,
                                        k: Int,
                                        sqlClause: String,
                                        filter: java.util.Map[DegreeFilterTypes, Integer],
                                        alternating: Boolean): util.List[Node] =
    kStepNeighborsFiltered(edgeType, source, k, sqlClause, EdgeDirIn,
                           CollectionUtil.toScala(filter), alternating)

  override def pprUndirected(edgeTypes: util.List[String],
                             seedNodesJava: util.List[Node],
                             pageRankParams: MonteCarloPageRankParams): util.Map[Node, lang.Double] = {
    validateMonteCarloParams(pageRankParams)
    val seedNodes = seedNodesJava.asScala
    val seeds = databaseClient.nodeToIntNodeMap(seedNodes).values.toIndexedSeq

    val typedGraphs = edgeTypes.asScala map typedGraph
    val unionGraph = new TypedGraphUnion(typedGraphs)
    val pprMap = MonteCarloPPRTyped.estimatePPR(unionGraph, seeds, pageRankParams)
    val intNodeToNodeMap = databaseClient.intNodeToNodeMap(pprMap.keys)
    (pprMap map { case (intNode, value) =>
      (intNodeToNodeMap(intNode), new lang.Double(value))
    }).asJava
  }

  def satisfiesFilters(edgeType: String, nodeId: Long, degreeFilter: DegreeFilter): Boolean =
    degreeFilter.forall {case(filterType, filterValue) =>
      satisfiesSingleFilter(edgeType, nodeId, filterType, filterValue)
    }

  def satisfiesSingleFilter(edgeType: String, nodeId: Long, filterType: DegreeFilterTypes, filterValue: Int): Boolean =
    filterType match { // TODO: Support multiple graphs here
      case DegreeFilterTypes.INDEGREE_MAX => graph(edgeType).inDegree(nodeId.toInt) <= filterValue
      case DegreeFilterTypes.INDEGREE_MIN => graph(edgeType).inDegree(nodeId.toInt) >= filterValue
      case DegreeFilterTypes.OUTDEGREE_MAX => graph(edgeType).outDegree(nodeId.toInt) <= filterValue
      case DegreeFilterTypes.OUTDEGREE_MIN => graph(edgeType).outDegree(nodeId.toInt) >= filterValue
      case default => true
    }

  def validateMonteCarloParams(params: MonteCarloPageRankParams): Unit = {
    if (params.resetProbability >= 1.0 || params.resetProbability <= 0.0) {
      throw new InvalidArgumentException("resetProbability must be between 0.0 and 1.0")
    }
    if (params.numSteps <= 0) {
      throw new InvalidArgumentException("numSteps must be positive")
    }
    if (params.isSetMaxResultCount && params.maxResultCount <= 0) {
      throw new InvalidArgumentException("maxResultCount must be positive")
    }
  }

  override def connectedComponent(sourceNode: Node, edgeTypes: util.List[String], maxSize: Int): util.List[Node] = {
    val source = databaseClient.nodeToIntNode(sourceNode)

    val typedGraphs = edgeTypes.asScala map typedGraph
    val unionGraph = new TypedGraphUnion(typedGraphs)

    val reachedNodes = new mutable.HashSet[IntNode]()
    val nodesToVisit = new util.ArrayDeque[IntNode]()
    reachedNodes += source
    nodesToVisit.push(source)
    while (! nodesToVisit.isEmpty && reachedNodes.size < maxSize) {
      val u = nodesToVisit.pop()
      for (v <- unionGraph.neighbors(u)) {
        if (! reachedNodes.contains(v) && reachedNodes.size < maxSize) {
          reachedNodes += v
          nodesToVisit.push(v)
        }
      }
    }
    val resultNodes = databaseClient.intNodeToNodeMap(reachedNodes).values
    new util.ArrayList(resultNodes.asJavaCollection)
  }

  override def addEdges(edgeType: String,
                        sourceNodesJava: util.List[Node],
                        targetNodesJava: util.List[Node]): Unit = {
    if (sourceNodesJava.size != targetNodesJava.size) {
      throw new UnequalListSizeException()
    }
    val sourceNodes = sourceNodesJava.asScala
    val targetNodes = targetNodesJava.asScala

    val nodeToIntNodeMap = databaseClient.nodeToIntNodeMap(sourceNodes ++ targetNodes)
    val sourceTempestIds = sourceNodes map { node: Node => nodeToIntNodeMap(node).tempestId }
    val targetTempestIds = targetNodes map { node: Node => nodeToIntNodeMap(node).tempestId }

    // TODO: Add edges to DB?
    // databaseClient.addEdges(graphName: String, CollectionUtil.toScala(sourceIds), CollectionUtil.toScala(destinationIds))
    for ((sourceId, targetId) <- sourceTempestIds zip targetTempestIds) {
      graph(edgeType).addEdge(sourceId, targetId) // Future optimization: efficient Multi-add
    }
  }
}

object TempestDBServer {
  def getProcessor(configFileName: String): TProcessor = {
    val config = ConfigLoader.loadConfig[TempestDBServerConfig](configFileName)
    // Not currently used: ConfigLoader.loadConfig[TempestDBServerConfig](configFileName)
    val databaseConfigFile = "/root/tempest/system/database.yaml" // TODO: move db config to main config?
    val databaseConfig = ConfigLoader.loadConfig[DatabaseConfig](databaseConfigFile)
    val databaseClient = new TempestSQLDatabaseClient(databaseConfig)

    val server = new TempestDBServer(databaseClient, config)
    new TempestDBService.Processor(server)
  }

  def main(args: Array[String]): Unit = {
    LogUtil.configureLog4j()
    new TeapotThriftLauncher().launch(args, getProcessor, "/root/tempest/system/tempest.yaml")
  }
}

object TempestServerConstants {
  val MaxNeighborhoodAttributeQuerySize = 1000 * 1000
}
