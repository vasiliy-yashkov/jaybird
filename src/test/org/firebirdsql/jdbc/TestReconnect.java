/*
 * Firebird Open Source JavaEE Connector - JDBC Driver
 *
 * Distributable under LGPL license.
 * You may obtain a copy of the License at http://www.gnu.org/copyleft/lgpl.html
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * LGPL License for more details.
 *
 * This file was created by members of the firebird development team.
 * All individual contributions remain the Copyright (C) of those
 * individuals.  Contributors to this file are either listed here or
 * can be obtained from a source control history command.
 *
 * All rights reserved.
 */
package org.firebirdsql.jdbc;

import org.firebirdsql.common.FBJUnit4TestBase;
import org.junit.After;
import org.junit.Test;

import java.sql.*;
import java.util.Random;

import static org.firebirdsql.common.FBTestProperties.getConnectionViaDriverManager;

public class TestReconnect extends FBJUnit4TestBase {

    private static final int TABLE_COUNT = 10;

    private Connection con;

    @After
    public void tearDown() throws SQLException {
        if (con != null) {
            con.close();
        }
    }

    private static String getTableName(int no) {
        return "TEST" + no;
    }

    private void execute(String sql) throws SQLException {
        try (Statement stmt = con.createStatement()) {
            stmt.executeUpdate(sql);
        } catch (SQLException e) {
            if (log != null) {
                log.info("Error executing:" + sql);
            }
            throw e;
        }
    }

    private void execute(String sql, boolean retryOnError) throws SQLException {
        if (retryOnError) {
            try {
                execute(sql);
            } catch (SQLException e) {
                // Workaround for the well-known "object in use" error
                // (see release notes of Firebird 1.5)
                log.info("Retrying after reconnecting ...", e);
                boolean oldAutoCommit = con.getAutoCommit();
                con.close();
                openConnection();
                if (con.getAutoCommit() != oldAutoCommit)
                    con.setAutoCommit(oldAutoCommit);
                log.info("reexecuting sql-statement on new connection:");
                log.info(sql);
                execute(sql);
                // Here the program hangs (see socketRead), and
                // pressing ctrl-break I get the following output:
                // ---------

                //Full thread dump:
                //
                //"Signal Dispatcher" daemon prio=10 tid=0x8f2ec0 nid=0x29c waiting on monitor [0..0]
                //
                //"Finalizer" daemon prio=9 tid=0x8f1098 nid=0x2ec waiting on monitor [0x8cdf000..0x8cdfdbc]
                //        at java.lang.Object.wait(Native Method)
                //        at java.lang.ref.ReferenceQueue.remove(ReferenceQueue.java:108)
                //        at java.lang.ref.ReferenceQueue.remove(ReferenceQueue.java:123)
                //        at java.lang.ref.Finalizer$FinalizerThread.run(Finalizer.java:162)
                //
                //"Reference Handler" daemon prio=10 tid=0x8a20c68 nid=0x2bc waiting on monitor [0x8c9f000..0x8c9fdbc]
                //        at java.lang.Object.wait(Native Method)
                //        at java.lang.Object.wait(Object.java:420)
                //        at java.lang.ref.Reference$ReferenceHandler.run(Reference.java:110)
                //
                //"main" prio=5 tid=0x346d8 nid=0x550 runnable [0x7f000..0x7fc34]
                //        at java.net.SocketInputStream.socketRead(Native Method)
                //        at java.net.SocketInputStream.read(SocketInputStream.java:86)
                //        at java.io.BufferedInputStream.fill(BufferedInputStream.java:186)
                //        at java.io.BufferedInputStream.read(BufferedInputStream.java:204)
                //        at java.io.DataInputStream.readInt(DataInputStream.java:338)
                //        at org.firebirdsql.jgds.GDS_Impl.nextOperation(GDS_Impl.java:1471)
                //        at org.firebirdsql.jgds.GDS_Impl.isc_dsql_execute2(GDS_Impl.java:728)
                //        at org.firebirdsql.jca.FBManagedConnection.executeStatement(FBManagedConnection.java:593)
                //        at org.firebirdsql.jdbc.FBConnection.executeStatement(FBConnection.java:1104)
                //        at org.firebirdsql.jdbc.FBStatement.internalExecute(FBStatement.java:929)
                //        at org.firebirdsql.jdbc.FBStatement.executeUpdate(FBStatement.java:146)
                //        at TestReconnect.execute(TestReconnect.java:19)
                //        at TestReconnect.execute(TestReconnect.java:58)
                //        at TestReconnect.alterForeignKeys(TestReconnect.java:192)
                //        at TestReconnect.run(TestReconnect.java:314)
                //        at TestReconnect.main(TestReconnect.java:337)
                //
                //"VM Thread" prio=5 tid=0x91b8f8 nid=0x5bc runnable
                //
                //"VM Periodic Task Thread" prio=10 tid=0x8f2c60 nid=0x30c waiting on monitor
                //"Suspend Checker Thread" prio=10 tid=0x8f2d80 nid=0x1d8 runnable
                // ---------
            }
        } else
            execute(sql);
    }

    private boolean tableExists(String tableName) throws SQLException {
        boolean exists = false;
        ResultSet rs = con.getMetaData().getTables(null, null, tableName, null);
        if (rs != null) {
            exists = rs.next();
            rs.close();
        }
        return exists;
    }

    private void dropTestTables() throws SQLException {
        if (log != null)
            log.info("Dropping test tables ...");
        if (!con.getAutoCommit())
            con.setAutoCommit(true);
        for (int i = TABLE_COUNT; i > 0; i--) {
            String tableName = getTableName(i);
            if (tableExists(tableName))
                execute("DROP TABLE " + tableName, true);
        }
    }

    private void createTestTables() throws SQLException {
        log.info("Creating test tables ...");
        if (!con.getAutoCommit())
            con.setAutoCommit(true);

        for (int i = 1; i <= TABLE_COUNT; i++) {
            String table = getTableName(i);
            StringBuilder sql = new StringBuilder(100);
            sql.append("CREATE TABLE ");
            sql.append(table);
            sql.append(" (\n" +
                    "ID INTEGER NOT NULL,\n" +
                    "NR INTEGER NOT NULL,\n" +
                    "X1 VARCHAR(50),\n" +
                    "X2 VARCHAR(50),\n" +
                    "X3 VARCHAR(50),\n");
            if (i > 1) {
                sql.append("ID_");
                sql.append(getTableName(i - 1));
                sql.append(" INTEGER,\n");
            }
            sql.append("CONSTRAINT PK_");
            sql.append(table);
            sql.append(" PRIMARY KEY (ID))");
            execute(sql.toString());
        }
        // add FOREIGN KEY's
        for (int i = 2; i <= TABLE_COUNT; i++) {
            String thisTable = getTableName(i);
            String refTable = getTableName(i - 1);
            String sql = "ALTER TABLE " + thisTable +
                    " ADD CONSTRAINT FK_" + refTable +
                    " FOREIGN KEY (ID_" + refTable + ") REFERENCES " + refTable + " (ID)";
            execute(sql, true);
        }
    }

    private void alterForeignKeys(boolean cascade) throws SQLException {
        log.info("Altering foreign keys ...");
        if (!con.getAutoCommit())
            con.setAutoCommit(true);
        // add FOREIGN KEY's
        for (int i = 2; i <= TABLE_COUNT; i++) {
            String thisTable = getTableName(i);
            String refTable = getTableName(i - 1);
            String sql = "ALTER TABLE " + thisTable +
                    " DROP CONSTRAINT FK_" + refTable;
            execute(sql, true);

            String sql2 = "ALTER TABLE " + thisTable +
                    " ADD CONSTRAINT FK_" + refTable +
                    " FOREIGN KEY (ID_" + refTable + ") REFERENCES " + refTable + " (ID)" +
                    (cascade ? " ON DELETE CASCADE" : "");
            execute(sql2, true);
        }
    }

    private void populateTestTables(int rowCount) throws SQLException {
        if (con.getAutoCommit())
            con.setAutoCommit(false);
        log.info("Populating test tables ...");
        Random random = new Random();
        for (int i = 1; i <= TABLE_COUNT; i++) {
            StringBuilder sql = new StringBuilder(100);
            sql.append("INSERT INTO ");
            sql.append(getTableName(i));
            sql.append(" (ID,NR,X1,X2,X3");
            if (i > 1) {
                sql.append(",ID_");
                sql.append(getTableName(i - 1));
            }
            sql.append(") VALUES (?,?,?,?,?");
            if (i > 1)
                sql.append(",?");
            sql.append(")");
            PreparedStatement stmt = con.prepareStatement(sql.toString());
            for (int row = 1; row <= rowCount; row++) {
                int rndValue = random.nextInt(1000);
                stmt.setInt(1, row);
                stmt.setInt(2, rndValue);
                stmt.setString(3, "X1." + row + "." + rndValue);
                stmt.setString(4, "X2." + row + "." + rndValue);
                stmt.setString(5, "X3." + row + "." + rndValue);
                if (i > 1)
                    stmt.setInt(6, row);
                stmt.executeUpdate();
            }
            stmt.close();
            con.commit();
        }
    }

    private void readResult(String title, ResultSet rs, boolean print) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int cols = md.getColumnCount();
        if (print) {
            log.info(title);
            log.info("-------------------------------------------------------------------------------");
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= cols; i++) {
                if (i > 1)
                    sb.append('\t');
                sb.append(md.getColumnLabel(i));
            }
            log.info(sb.toString());
            log.info("-------------------------------------------------------------------------------");
        }
        while (rs.next()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= cols; i++) {
                String value = rs.getString(i);

                if (i > 1)
                    sb.append('\t');
                sb.append(value);

            }
            log.info(sb.toString());
        }
        rs.close();
    }

    private void readMetaData() throws SQLException {
        log.info("Reading meta data ...");
        DatabaseMetaData md = con.getMetaData();
        for (int i = 1; i <= TABLE_COUNT; i++) {
            String tableName = getTableName(i);
            boolean print = i == 1;
            readResult("COLUMNS of " + tableName, md.getColumns(null, null, tableName, null), print);
            readResult("INDEX INFO of " + tableName, md.getIndexInfo(null, null, tableName, false, false), print);
            readResult("PRIMARY KEYS of " + tableName, md.getPrimaryKeys(null, null, tableName), print);
            readResult("IMPORTED KEYS of " + tableName, md.getImportedKeys(null, null, tableName), print);
            readResult("EXPORTED KEYS of " + tableName, md.getExportedKeys(null, null, tableName), print);
        }
    }

    private void openConnection() throws SQLException {
        con = getConnectionViaDriverManager();
        con.setAutoCommit(true);
        con.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    }

    @Test
    public void testReconnect() throws Exception {
        openConnection();
        dropTestTables();
        createTestTables();
        populateTestTables(100);

        readResult("TYPE INFO", con.getMetaData().getTypeInfo(), true);
        readMetaData();
        alterForeignKeys(true);
        readMetaData();
        alterForeignKeys(false);
    }
}
