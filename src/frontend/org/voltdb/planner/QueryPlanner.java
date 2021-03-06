/* This file is part of VoltDB.
 * Copyright (C) 2008-2012 VoltDB Inc.
 *
 * VoltDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VoltDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.planner;

import java.io.File;
import java.util.List;

import org.hsqldb_voltpatches.HSQLInterface;
import org.hsqldb_voltpatches.HSQLInterface.HSQLParseException;
import org.hsqldb_voltpatches.VoltXMLElement;
import org.json_voltpatches.JSONException;
import org.json_voltpatches.JSONObject;
import org.voltdb.catalog.Cluster;
import org.voltdb.catalog.Database;
import org.voltdb.compiler.DatabaseEstimates;
import org.voltdb.compiler.ScalarValueHints;
import org.voltdb.expressions.AbstractExpression;
import org.voltdb.expressions.ParameterValueExpression;
import org.voltdb.planner.microoptimizations.MicroOptimizationRunner;
import org.voltdb.plannodes.AbstractPlanNode;
import org.voltdb.plannodes.PlanNodeList;
import org.voltdb.utils.BuildDirectoryUtils;

/**
 * The query planner accepts catalog data, SQL statements from the catalog, then
 * outputs the plan with the lowest cost according to the cost model.
 *
 */
public class QueryPlanner {
    PlanAssembler m_assembler;
    HSQLInterface m_HSQL;
    DatabaseEstimates m_estimates;
    Cluster m_cluster;
    Database m_db;
    String m_recentErrorMsg;
    boolean m_quietPlanner;
    final boolean m_fullDebug;
    PartitioningForStatement m_partitioning;

    /**
     * Initialize planner with physical schema info and a reference to HSQLDB parser.
     *
     * @param catalogCluster Catalog info about the physical layout of the cluster.
     * @param catalogDb Catalog info about schema, metadata and procedures.
     * @param partitioning Describes the specified and inferred partition context.
     * @param HSQL HSQLInterface pointer used for parsing SQL into XML.
     * @param useGlobalIds
     */
    public QueryPlanner(Cluster catalogCluster, Database catalogDb, PartitioningForStatement partitioning,
                        HSQLInterface HSQL, DatabaseEstimates estimates,
                        boolean suppressDebugOutput) {
        assert(HSQL != null);
        assert(catalogCluster != null);
        assert(catalogDb != null);

        m_HSQL = HSQL;
        m_assembler = new PlanAssembler(catalogCluster, catalogDb, partitioning);
        m_db = catalogDb;
        m_cluster = catalogCluster;
        m_estimates = estimates;
        m_quietPlanner = suppressDebugOutput;
        m_partitioning = partitioning;
        m_fullDebug = System.getProperties().contains("compilerdebug");
    }

    /**
     * Get the best plan for the SQL statement given, assuming the given costModel.
     *
     * @param costModel The current cost model to evaluate plans with.
     * @param sql SQL stmt text to be planned.
     * @param sql Suggested join order to be used for the query
     * @param stmtName The name of the sql statement to be planned.
     * @param procName The name of the procedure containing the sql statement to be planned.
     * @param singlePartition Is the stmt single-partition?
     * @param paramHints
     * @return The best plan found for the SQL statement or null if none can be found.
     */
    public CompiledPlan compilePlan(
            AbstractCostModel costModel,
            String sql,
            String joinOrder,
            String stmtName,
            String procName,
            int maxTablesPerJoin,
            ScalarValueHints[] paramHints,
            boolean parameterize) {
        assert(costModel != null);
        assert(sql != null);
        assert(stmtName != null);
        assert(procName != null);

        // reset any error message
        m_recentErrorMsg = null;

        // Reset plan node ids to start at 1 for this plan
        AbstractPlanNode.resetPlanNodeIds();

        // use HSQLDB to get XML that describes the semantics of the statement
        // this is much easier to parse than SQL and is checked against the catalog
        VoltXMLElement xmlSQL = null;
        try {
            xmlSQL = m_HSQL.getXMLCompiledStatement(sql);
        } catch (HSQLParseException e) {
            // XXXLOG probably want a real log message here
            m_recentErrorMsg = e.getMessage();
            return null;
        }

        if (!m_quietPlanner && m_fullDebug) {
            outputCompiledStatement(stmtName, procName, xmlSQL);
        }

        // what's going to happen next:
        //  Try to parameterize the constant value expressions (if asked to do so)
        //  On success return the plan.
        //  On failure, try the plan again without parameterization
        //  note, this means copies might need to be made of anything mutated in
        //   the planning process, specifically the VoltXML object and maybe the partitioning info

        if (parameterize) {
            // make a copy so it can be mutated
            VoltXMLElement parameterizedXmlSQL = xmlSQL.duplicate();

            Parameterizer pzer = new Parameterizer(parameterizedXmlSQL);
            int preExistingParams = pzer.countParams();

            // skip plans with pre-existing parameters
            // assume a user knows how to cache/optimize these
            if (preExistingParams == 0) {
                Object[] extractedParams = null;

                extractedParams = pzer.parameterize();

                // if requested output the second version of the parsed plan
                if (!m_quietPlanner && m_fullDebug) {
                    outputParameterizedCompiledStatement(stmtName, procName, xmlSQL);
                }

                try {
                    // compile the plan with new parameters
                    CompiledPlan plan = compileFromXML(parameterizedXmlSQL, costModel,
                            sql, joinOrder, stmtName, procName, maxTablesPerJoin, paramHints);

                    // the extracted params are all strings at first.
                    // after the planner infers their types, fix them up
                    // the only exception is that nulls are Java NULL, and not the string "null".
                    for (int i = 0; i < plan.parameters.length; i++) {
                        extractedParams[i] = Parameterizer.valueForStringWithType((String)extractedParams[i], plan.parameters[i]);
                    }
                    plan.extractedParamValues.setParameters(extractedParams);

                    // handle the case where the statement is partitioned on a newly parameterized value
                    if (m_partitioning.effectivePartitioningValue() == null) {
                        AbstractExpression expr = m_partitioning.effectivePartitioningExpression();
                        if (expr != null) {
                            if (expr instanceof ParameterValueExpression) {
                                ParameterValueExpression pve = (ParameterValueExpression) expr;
                                Object partitionValue = plan.extractedParamValues.toArray()[pve.getParameterIndex()];
                                plan.setPartitioningKey(partitionValue);
                            }
                        }
                    }

                    return plan;
                }
                catch (Exception e) {
                    // ignore any errors planning with parameters
                    // fall through to re-planning without them
                }
            }
        }

        // if parameterization isn't requested or if it failed, plan here
        return compileFromXML(xmlSQL, costModel, sql, joinOrder, stmtName,
                procName, maxTablesPerJoin, paramHints);
    }

    private CompiledPlan compileFromXML(
            VoltXMLElement xmlSQL,
            AbstractCostModel costModel,
            String sql,
            String joinOrder,
            String stmtName,
            String procName,
            int maxTablesPerJoin,
            ScalarValueHints[] paramHints)
    {
        // Get a parsed statement from the xml
        // The callers of compilePlan are ready to catch any exceptions thrown here.
        AbstractParsedStmt parsedStmt = AbstractParsedStmt.parse(sql, xmlSQL, m_db, joinOrder);
        if (parsedStmt == null)
        {
            m_recentErrorMsg = "Failed to parse SQL statement: " + sql;
            return null;
        }
        if ((parsedStmt.tableList.size() > maxTablesPerJoin) && (parsedStmt.joinOrder == null)) {
            m_recentErrorMsg = "Failed to parse SQL statement: " + sql + " because a join of > 5 tables was requested"
                               + " without specifying a join order. See documentation for instructions on manually" +
                                 " specifying a join order";
            return null;
        }

        if (!m_quietPlanner && m_fullDebug) {
            outputParsedStatement(stmtName, procName, parsedStmt);
        }

        // get ready to find the plan with minimal cost
        CompiledPlan rawplan = null;
        CompiledPlan bestPlan = null;
        String bestFilename = null;
        double minCost = Double.MAX_VALUE;

        // index of the plan currently being "costed"
        int planCounter = 0;

        PlanStatistics stats = null;

        // set up the plan assembler for this statement
        m_assembler.setupForNewPlans(parsedStmt);

        // loop over all possible plans
        while (true) {

            try {
                rawplan = m_assembler.getNextPlan();
            }
            // on exception, set the error message and bail...
            catch (PlanningErrorException e) {
                m_recentErrorMsg = e.getMessage();
                return null;
            }

            // stop this while loop when no more plans are generated
            if (rawplan == null)
                break;

            // run the set of microptimizations, which may return many plans (or not)
            List<CompiledPlan> optimizedPlans = MicroOptimizationRunner.applyAll(rawplan);

            // iterate through the subset of plans
            for (CompiledPlan plan : optimizedPlans) {

                // add in the sql to the plan
                plan.sql = sql;

                // this plan is final, resolve all the column index references
                plan.rootPlanGraph.resolveColumnIndexes();

                // compute resource usage using the single stats collector
                stats = new PlanStatistics();
                AbstractPlanNode planGraph = plan.rootPlanGraph;

                // compute statistics about a plan
                boolean result = planGraph.computeEstimatesRecursively(stats, m_cluster, m_db, m_estimates, paramHints);
                assert(result);

                // compute the cost based on the resources using the current cost model
                plan.cost = costModel.getPlanCost(stats);

                // filename for debug output
                String filename = String.valueOf(planCounter++);

                // find the minimum cost plan
                if (plan.cost < minCost) {
                    minCost = plan.cost;
                    // free the PlanColumns held by the previous best plan
                    bestPlan = plan;
                    bestFilename = filename;
                }

                if (!m_quietPlanner) {
                    if (m_fullDebug) {
                        outputPlanFullDebug(plan, planGraph, stmtName, procName, filename);
                    }

                    // get the explained plan for the node
                    plan.explainedPlan = planGraph.toExplainPlanString();
                    outputExplainedPlan(stmtName, procName, plan, filename);
                }
            }
        }

        // make sure we got a winner
        if (bestPlan == null) {
            m_recentErrorMsg = "Unable to plan for statement. Error unknown.";
            return null;
        }

        // reset all the plan node ids for a given plan
        // this makes the ids deterministic
        bestPlan.resetPlanNodeIds();

        if (!m_quietPlanner)
        {
            finalizeOutput(stmtName, procName, bestFilename, stats);
        }

        // split up the plan everywhere we see send/recieve into multiple plan fragments
        Fragmentizer.fragmentize(bestPlan, m_db);
        return bestPlan;
    }

    /**
     * @param stmtName
     * @param procName
     * @param plan
     * @param filename
     */
    private void outputExplainedPlan(String stmtName, String procName, CompiledPlan plan, String filename) {
        BuildDirectoryUtils.writeFile("statement-all-plans/" + procName + "_" + stmtName,
                                      filename + ".txt",
                                      plan.explainedPlan);
    }

    /**
     * @param stmtName
     * @param procName
     * @param parsedStmt
     */
    private void outputParsedStatement(String stmtName, String procName, AbstractParsedStmt parsedStmt) {
        // output a description of the parsed stmt
        BuildDirectoryUtils.writeFile("statement-parsed", procName + "_" + stmtName + ".txt", parsedStmt.toString());
    }

    /**
     * @param stmtName
     * @param procName
     * @param xmlSQL
     */
    private void outputCompiledStatement(String stmtName, String procName, VoltXMLElement xmlSQL) {
        // output the xml from hsql to disk for debugging
        BuildDirectoryUtils.writeFile("statement-hsql-xml", procName + "_" + stmtName + ".xml", xmlSQL.toString());
    }

    /**
     * @param stmtName
     * @param procName
     * @param xmlSQL
     */
    private void outputParameterizedCompiledStatement(String stmtName, String procName, VoltXMLElement xmlSQL) {
        // output the xml from hsql to disk for debugging
        BuildDirectoryUtils.writeFile("statement-hsql-xml", procName + "_" + stmtName + "-parameterized.xml", xmlSQL.toString());
    }

    /**
     * @param plan
     * @param planGraph
     * @param stmtName
     * @param procName
     * @param filename
     */
    private void outputPlanFullDebug(CompiledPlan plan, AbstractPlanNode planGraph, String stmtName, String procName, String filename) {
        // GENERATE JSON DEBUGGING OUTPUT BEFORE WE CLEAN UP THE
        // PlanColumns
        // convert a tree into an execution list
        PlanNodeList nodeList = new PlanNodeList(planGraph);

        // get the json serialized version of the plan
        String json = null;

        try {
            String crunchJson = nodeList.toJSONString();
            //System.out.println(crunchJson);
            //System.out.flush();
            JSONObject jobj = new JSONObject(crunchJson);
            json = jobj.toString(4);
        } catch (JSONException e2) {
            // Any plan that can't be serialized to JSON to
            // write to debugging output is also going to fail
            // to get written to the catalog, to sysprocs, etc.
            // Just bail.
            m_recentErrorMsg = "Plan for sql: '" + plan.sql +
                               "' can't be serialized to JSON";
            // This case used to exit the planner
            // -- a strange behavior for something that only gets called when full debug output is enabled.
            // For now, just skip the output and go on to the next plan.
            return;
        }

        // output a description of the parsed stmt
        json = "PLAN:\n" + json;
        json = "COST: " + String.valueOf(plan.cost) + "\n" + json;
        assert (plan.sql != null);
        json = "SQL: " + plan.sql + "\n" + json;

        // write json to disk
        BuildDirectoryUtils.writeFile("statement-all-plans/" + procName + "_" + stmtName,
                                      filename + "-json.txt",
                                      json);

        // create a graph friendly version
        BuildDirectoryUtils.writeFile("statement-all-plans/" + procName + "_" + stmtName,
                                      filename + ".dot",
                                      nodeList.toDOTString("name"));
    }

    /**
     * @param filename
     * @param filenameRenamed
     */
    private void renameFile(String filename, String filenameRenamed) {
        File file;
        File fileRenamed;
        file = new File(filename);
        fileRenamed = new File(filenameRenamed);
        file.renameTo(fileRenamed);
    }

    /**
     * @param stmtName
     * @param procName
     * @param bestFilename
     * @param stats
     */
    private void finalizeOutput(String stmtName, String procName, String bestFilename, PlanStatistics stats) {
        // find out where debugging is going
        String prefix = BuildDirectoryUtils.getBuildDirectoryPath() +
                "/" + BuildDirectoryUtils.rootPath + "statement-all-plans/" +
                procName + "_" + stmtName + "/";
        String winnerFilename, winnerFilenameRenamed;

        // if outputting full stuff
        if (m_fullDebug) {
            // rename the winner json plan
            winnerFilename = prefix + bestFilename + "-json.txt";
            winnerFilenameRenamed = prefix + "WINNER-" + bestFilename + "-json.txt";
            renameFile(winnerFilename, winnerFilenameRenamed);

            // rename the winner dot plan
            winnerFilename = prefix + bestFilename + ".dot";
            winnerFilenameRenamed = prefix + "WINNER-" + bestFilename + ".dot";
            renameFile(winnerFilename, winnerFilenameRenamed);
        }

        // rename the winner explain plan
        winnerFilename = prefix + bestFilename + ".txt";
        winnerFilenameRenamed = prefix + "WINNER-" + bestFilename + ".txt";
        renameFile(winnerFilename, winnerFilenameRenamed);

        if (m_fullDebug) {
            // output the plan statistics to disk for debugging
            BuildDirectoryUtils.writeFile("statement-stats", procName + "_" + stmtName + ".txt", stats.toString());
        }
    }

    public String getErrorMessage() {
        return m_recentErrorMsg;
    }
}
