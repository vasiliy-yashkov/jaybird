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

import java.sql.SQLException;
import javax.resource.ResourceException;
import org.firebirdsql.gds.GDSException;
import org.firebirdsql.jca.FBResourceException;
import java.io.PrintWriter;
import java.io.PrintStream;
import java.io.IOException;

/**
 *@author Ken Richard
 *@created May 30, 2002
 *@see <related>
 *@version $ $
 */

public class FBSQLException extends SQLException {
    
    public static final String SQL_STATE_INVALID_CONN_ATTR = "01S00";
    public static final String SQL_STATE_NO_ROW_AVAIL = "01S06";
    
    public static final String SQL_STATE_GENERAL_ERROR = "HY000";
    public static final String SQL_STATE_INVALID_COLUMN = "HY002";
    public static final String SQL_STATE_INVALID_PARAM_TYPE = "HY105";
    public static final String SQL_STATE_INVALID_ARG_VALUE = "HY009";
    
    public static final String SQL_STATE_WRONG_PARAM_NUM = "07001";
    public static final String SQL_STATE_NO_RESULT_SET = "07005";
    public static final String SQL_STATE_INVALID_CONVERSION = "07006";
    
    public static final String SQL_STATE_CONNECTION_CLOSED = "08003";
    public static final String SQL_STATE_CONNECTION_FAILURE_IN_TX = "08007";
    public static final String SQL_STATE_COMM_LINK_FAILURE = "08S01";
    
    private Exception original;
    private String message;
    
    private String sqlState;
    
    public FBSQLException(IOException ioex) {
        super(ioex.getMessage());
        original = ioex;
        message = "I/O Exception. " + ioex.getMessage();
    }

    public FBSQLException(GDSException ex) {
        super(ex.getMessage());
        original = ex;
        message = "GDS Exception "+ ex.getIntParam() + ". " + ex.getMessage();
    }

    public FBSQLException(ResourceException ex) {
        super(ex.getMessage(), 
                ex.getErrorCode() != null ? ex.getErrorCode() : 
                                          SQL_STATE_GENERAL_ERROR);
        
        // try to unwrap wrapped exception
        if (ex instanceof FBResourceException) {
            
            FBResourceException rex = (FBResourceException)ex;
            
            if (rex.getLinkedException() != null)
                original = rex.getLinkedException();
            else
                original = rex;
                
        } else
            original = ex;
            
        message = "Resource Exception. " + ex.getMessage();
    }
    
    public FBSQLException(String message) {
        super(message, SQL_STATE_GENERAL_ERROR);
    }

    public FBSQLException(String message, String sqlState) {
        super(message, sqlState);
    }
    
    public int getErrorCode() {
        if (original instanceof GDSException)
            return ((GDSException)original).getIntParam();
        else
            return 0;
    }
    
    public Exception getInternalException() {
        return original;
    }

    public String getMessage() {
        return message;
    }

    public void printStackTrace() {
        printStackTrace(System.err);
    }

    public void printStackTrace(PrintStream s) {
        super.printStackTrace(s);
        if (original != null) {
            s.print("at ");
            original.printStackTrace(s);
        }
    }

    public void printStackTrace(PrintWriter s) {
        super.printStackTrace(s);
        if (original != null) {
            s.print("at ");
            original.printStackTrace(s);
        }
    }
}
