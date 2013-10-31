/*
 * QCRI, NADEEF LICENSE
 * NADEEF is an extensible, generalized and easy-to-deploy data cleaning platform built at QCRI.
 * NADEEF means "Clean" in Arabic
 *
 * Copyright (c) 2011-2013, Qatar Foundation for Education, Science and Community Development (on
 * behalf of Qatar Computing Research Institute) having its principle place of business in Doha,
 * Qatar with the registered address P.O box 5825 Doha, Qatar (hereinafter referred to as "QCRI")
 *
 * NADEEF has patent pending nevertheless the following is granted.
 * NADEEF is released under the terms of the MIT License, (http://opensource.org/licenses/MIT).
 */

package qa.qcri.nadeef.core.pipeline;

import qa.qcri.nadeef.core.datamodel.Fix;
import qa.qcri.nadeef.core.datamodel.NadeefConfiguration;
import qa.qcri.nadeef.core.util.sql.DBConnectionPool;
import qa.qcri.nadeef.tools.DBConfig;
import qa.qcri.nadeef.tools.Tracer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Collection;

/**
 * IncrementalUpdate does incremental insert/delete on the violation table to be able to run
 * incremental detection algorithm (for performance reason).
 */
public class IncrementalUpdate extends Operator<Collection<Fix>, int[]> {
    private DBConfig dbConfig;

    public IncrementalUpdate(DBConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected int[] execute(Collection<Fix> fixes) throws Exception {
        Connection conn = null;
        PreparedStatement stat = null;
        int[] newTuples = new int[fixes.size()];
        Tracer tracer = Tracer.getTracer(IncrementalUpdate.class);
        try {
            conn = DBConnectionPool.createConnection(dbConfig, false);
            stat = conn.prepareStatement("DELETE FROM "
                + NadeefConfiguration.getViolationTableName()
                + " WHERE vid IN (SELECT DISTINCT(vid) FROM "
                + NadeefConfiguration.getViolationTableName()
                + " WHERE tablename=? AND tupleid=?)");
            int count = 0;
            for (Fix fix : fixes) {
                int tid = fix.getLeft().getTupleId();
                String tableName = fix.getLeft().getColumn().getTableName();
                stat.setString(1, tableName);
                stat.setInt(2, tid);
                stat.addBatch();

                if (count % 4096 == 0) {
                    stat.executeBatch();
                    conn.commit();
                }
                count ++;
                newTuples[count] = fix.getLeft().getTupleId();
            }

            stat.executeBatch();
            conn.commit();
        } catch (Exception ex) {
            tracer.err("Incremental deletion failed.", ex);
        } finally {
            try {
                if (conn != null) {
                    conn.close();
                }

                if (stat != null) {
                    stat.close();
                }
            } catch (Exception ex) {}
        }
        return newTuples;
    }
}
