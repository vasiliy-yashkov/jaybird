/*
 * Firebird Open Source J2ee connector - jdbc driver
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
 * can be obtained from a CVS history command.
 *
 * All rights reserved.
 */

package org.firebirdsql.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Iterator;

import org.firebirdsql.pool.FBWrappingDataSource;

/**
 * Describe class <code>TestFBWrappingDataSource</code> here.
 *
 * @author <a href="mailto:rrokytskyy@users.sourceforge.net">Roman Rokytskyy</a>
 * @version 1.0
 */
public class TestFBWrappingDataSource extends BaseFBTest {

    private Connection connection;
    private FBWrappingDataSource ds;

    public TestFBWrappingDataSource(String testName) {
        super(testName);
    }


    public void testConnect() throws Exception {
        if (log != null) log.info("Testing FBWrapping DataSource on db: " + DB_DATASOURCE_URL);

        ds = new FBWrappingDataSource();
        ds.setDatabase(DB_DATASOURCE_URL);
        ds.setUserName(DB_USER);
        ds.setPassword(DB_PASSWORD);
        connection = ds.getConnection();
        assertTrue("Connection is null", connection != null);
        connection = ds.getConnection(DB_USER, DB_PASSWORD);
        assertTrue("Connection is null", connection != null);
    }

    public void testOneConnectionWithPooling() throws Exception {
        if (log != null) log.info("Testing FBWrapping DataSource Pooling on db: " + DB_DATASOURCE_URL);

        ds = new FBWrappingDataSource();
        ds.setDatabase(DB_DATASOURCE_URL);
        ds.setMinSize(0);
        ds.setMaxSize(5);
        ds.setBlockingTimeout(100);
        ds.setIdleTimeout(1000);
        ds.setPooling(true);
        connection = ds.getConnection(DB_USER, DB_PASSWORD);
        //connection.setAutoCommit(false);
        assertTrue("Connection is null", connection != null);
        Statement s = connection.createStatement();
        Exception ex = null;
        try {
           s.execute("CREATE TABLE T1 ( C1 SMALLINT, C2 SMALLINT)");
            //s.close();
            ResultSet rs = s.executeQuery("select * from T1");
            rs.close();
        }
        catch (Exception e) {
            ex = e;
        }
        //connection.commit();


        s.execute("DROP TABLE T1");
        s.close();
        //connection.commit();
        connection.close();
        if (ex != null) {
            throw ex;
        }

    }


   public void testPooling() throws Exception {
        if (log != null) log.info("Testing FBWrapping DataSource Pooling on db: " + DB_DATASOURCE_URL);

        ds = new FBWrappingDataSource();
        ds.setDatabase(DB_DATASOURCE_URL);
        ds.setMinSize(3);
        ds.setMaxSize(5);
        ds.setBlockingTimeout(1000);
        ds.setIdleTimeout(20000);
        ds.setPooling(true);
        ds.setUserName(DB_USER);
        ds.setPassword(DB_PASSWORD);
        connection = ds.getConnection();//DB_USER, DB_PASSWORD);
        assertTrue("Connection is null", connection != null);
        Thread.sleep(3000);
        int ccount = ds.getConnectionCount(); // should be 2, 3 total, but one is working
        assertTrue("Wrong number of connections! " + ccount + ", expected " + (ds.getMinSize() - 1), ccount == (ds.getMinSize() - 1));
        connection.close();
        ArrayList cs = new ArrayList();
        for (int i = 0; i < ds.getMaxSize(); i++)
        {
            cs.add(ds.getConnection());//DB_USER, DB_PASSWORD));
        } // end of for ()
        try
        {
            ds.getConnection();//DB_USER, DB_PASSWORD);
           fail("got a connection more than maxsize!");
        }
        catch (SQLException re)
        {
           //got a blocking timeout, good
        } // end of try-catch
        for (Iterator i = cs.iterator(); i.hasNext(); )
        {
           ((Connection)i.next()).close();
        } // end of for ()
        //This will be from same pool due to internal construction of FBDataSource.
        connection = ds.getConnection(DB_USER, DB_PASSWORD);
        assertTrue("Connection is null", connection != null);
        connection.close();

    }

}

