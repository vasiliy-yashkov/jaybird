/*
 * Firebird Open Source JDBC Driver
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
package org.firebirdsql.jaybird.xca;

import org.firebirdsql.gds.DatabaseParameterBuffer;
import org.firebirdsql.gds.ISCConstants;
import org.firebirdsql.gds.JaybirdSystemProperties;
import org.firebirdsql.gds.TransactionParameterBuffer;
import org.firebirdsql.gds.impl.DatabaseParameterBufferExtension;
import org.firebirdsql.gds.impl.DbAttachInfo;
import org.firebirdsql.gds.impl.GDSHelper;
import org.firebirdsql.gds.impl.jni.EmbeddedGDSFactoryPlugin;
import org.firebirdsql.gds.impl.jni.LocalGDSFactoryPlugin;
import org.firebirdsql.gds.ng.*;
import org.firebirdsql.gds.ng.fields.RowValue;
import org.firebirdsql.gds.ng.listeners.DefaultDatabaseListener;
import org.firebirdsql.gds.ng.listeners.DefaultStatementListener;
import org.firebirdsql.gds.ng.listeners.ExceptionListener;
import org.firebirdsql.jdbc.*;
import org.firebirdsql.jdbc.field.FBField;
import org.firebirdsql.jdbc.field.FieldDataProvider;
import org.firebirdsql.logging.Logger;
import org.firebirdsql.logging.LoggerFactory;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLWarning;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * A physical connection handle to a Firebird database, providing a {@code XAResource}.
 *
 * @author <a href="mailto:d_jencks@users.sourceforge.net">David Jencks</a>
 * @author <a href="mailto:mrotteveel@users.sourceforge.net">Mark Rotteveel</a>
 * @version 1.0
 */
public class FBManagedConnection implements ExceptionListener, Synchronizable {

    public static final String WARNING_NO_CHARSET = "WARNING: No connection character set specified (property lc_ctype, encoding, charSet or localEncoding), defaulting to character set ";
    public static final String ERROR_NO_CHARSET = "Connection rejected: No connection character set specified (property lc_ctype, encoding, charSet or localEncoding). "
            + "Please specify a connection character set (eg property charSet=utf-8) or consult the Jaybird documentation for more information.";

    private static final Logger log = LoggerFactory.getLogger(FBManagedConnection.class);

    private final FBManagedConnectionFactory mcf;

    private final List<XcaConnectionEventListener> connectionEventListeners = new CopyOnWriteArrayList<>();
    private static final AtomicReferenceFieldUpdater<FBManagedConnection, FBConnection> connectionHandleUpdater =
            AtomicReferenceFieldUpdater.newUpdater(FBManagedConnection.class, FBConnection.class, "connectionHandle");
    private volatile FBConnection connectionHandle;
    // This is a bit of hack to be able to get attach warnings into the FBConnection that is created later.
    private static final AtomicReferenceFieldUpdater<FBManagedConnection, SQLWarning> unnotifiedWarningsUpdater =
            AtomicReferenceFieldUpdater.newUpdater(FBManagedConnection.class, SQLWarning.class, "unnotifiedWarnings");
    private volatile SQLWarning unnotifiedWarnings;

    private int timeout = 0;

    private final Map<Xid, FbTransaction> xidMap = new ConcurrentHashMap<>();

    private GDSHelper gdsHelper;
    private final FbDatabase database;
    private final Object syncObject;
    private XAResource xaResource;
    private final FBConnectionRequestInfo cri;
    private FBTpbMapper transactionMapping;
    private FBTpb tpb;
    private int transactionIsolation;

    private volatile boolean managedEnvironment = true;
    private final Set<Xid> preparedXid = Collections.synchronizedSet(new HashSet<>());
    private volatile boolean inDistributedTransaction = false;

    FBManagedConnection(FBConnectionRequestInfo cri, FBManagedConnectionFactory mcf) throws SQLException {
        this.mcf = mcf;
        this.cri = getCombinedConnectionRequestInfo(cri);
        this.tpb = mcf.getDefaultTpb();
        this.transactionIsolation = mcf.getDefaultTransactionIsolation();

        //TODO: XIDs in limbo should be loaded so that XAER_DUPID can be thrown appropriately

        DatabaseParameterBuffer dpb = this.cri.getDpb();

        if (dpb.getArgumentAsString(DatabaseParameterBuffer.LC_CTYPE) == null
                && dpb.getArgumentAsString(DatabaseParameterBufferExtension.LOCAL_ENCODING) == null) {
            String defaultEncoding = getDefaultConnectionEncoding();
            if (defaultEncoding == null) {
                throw new SQLNonTransientConnectionException(ERROR_NO_CHARSET,
                        SQLStateConstants.SQL_STATE_CONNECTION_ERROR);
            }
            dpb.addArgument(DatabaseParameterBuffer.LC_CTYPE, defaultEncoding);

            String warningMessage = WARNING_NO_CHARSET + defaultEncoding;
            log.warn(warningMessage);
            notifyWarning(new SQLWarning(warningMessage));
        }

        if (!dpb.hasArgument(DatabaseParameterBuffer.CONNECT_TIMEOUT) && DriverManager.getLoginTimeout() > 0) {
            dpb.addArgument(DatabaseParameterBuffer.CONNECT_TIMEOUT, DriverManager.getLoginTimeout());
        }

        final FbConnectionProperties connectionProperties = new FbConnectionProperties();
        connectionProperties.fromDpb(dpb);
        // TODO Move this logic to the GDSType or database factory?
        final String gdsTypeName = mcf.getGDSType().toString();
        if (!(EmbeddedGDSFactoryPlugin.EMBEDDED_TYPE_NAME.equals(gdsTypeName)
                || LocalGDSFactoryPlugin.LOCAL_TYPE_NAME.equals(gdsTypeName))) {
            final DbAttachInfo dbAttachInfo = DbAttachInfo.parseConnectString(mcf.getDatabase());
            connectionProperties.setServerName(dbAttachInfo.getServer());
            connectionProperties.setPortNumber(dbAttachInfo.getPort());
            connectionProperties.setDatabaseName(dbAttachInfo.getFileName());
        } else {
            connectionProperties.setDatabaseName(mcf.getDatabase());
        }

        database = mcf.getDatabaseFactory().connect(connectionProperties);
        database.addDatabaseListener(new MCDatabaseListener());
        database.addExceptionListener(this);
        database.attach();
        syncObject = database.getSynchronizationObject();

        gdsHelper = new GDSHelper(database);
    }

    @Override
    public void errorOccurred(Object source, SQLException ex) {
        log.trace(ex.getMessage());

        if (!FatalGDSErrorHelper.isFatal(ex)) {
            return;
        }
        XcaConnectionEvent event = new XcaConnectionEvent(this, XcaConnectionEvent.EventType.CONNECTION_ERROR_OCCURRED,
                ex);

        notify(connectionErrorOccurredNotifier, event);
    }

    private FBConnectionRequestInfo getCombinedConnectionRequestInfo(FBConnectionRequestInfo cri) throws SQLException {
        if (cri == null) {
            return mcf.getDefaultConnectionRequestInfo();
        }
        return cri;
    }

    /**
     * Get instance of {@link GDSHelper} connected with this managed connection.
     *
     * @return instance of {@link GDSHelper}.
     * @throws SQLException
     *         If this connection has no GDSHelper
     */
    public GDSHelper getGDSHelper() throws SQLException {
        if (gdsHelper == null) {
            // TODO Right error code?
            throw new FbExceptionBuilder().exception(ISCConstants.isc_req_no_trans).toSQLException();
        }

        return gdsHelper;
    }

    public String getDatabase() {
        return mcf.getDatabase();
    }

    public boolean isManagedEnvironment() {
        return managedEnvironment;
    }

    public boolean inTransaction() {
        return gdsHelper != null && gdsHelper.inTransaction();
    }

    public void setManagedEnvironment(boolean managedEnvironment) throws SQLException {
        this.managedEnvironment = managedEnvironment;
        final FBConnection connection = connectionHandle;
        if (connection != null) {
            connection.setManagedEnvironment(managedEnvironment);
        }
    }

    /**
     * Returns a {@code FBLocalTransaction} instance.
     * <p>
     * The FBLocalTransaction is used by the container to manage local
     * transactions for a RM instance.
     * </p>
     *
     * @return FBLocalTransaction instance
     */
    public FBLocalTransaction getLocalTransaction() {
        return new FBLocalTransaction(this);
    }

    /**
     * Add an {@code XcaConnectionEventListener} listener. The listener will be notified when a
     * {@code XcaConnectionEvent} occurs.
     *
     * @param listener
     *         The {@code XcaConnectionEventListener} to be added
     */
    public void addConnectionEventListener(XcaConnectionEventListener listener) {
        connectionEventListeners.add(listener);
    }

    /**
     * Remove a {@code XcaConnectionEventListener} from the listing of listeners that will be notified for a
     * {@code XcaConnectionEvent}.
     *
     * @param listener
     *         The {@code FirebirdConnectionEventListener} to be removed
     */
    public void removeConnectionEventListener(XcaConnectionEventListener listener) {
        connectionEventListeners.remove(listener);
    }

    /**
     * Application server calls this method to force any cleanup on the managed connection instance.
     * <p>
     * The method {@code cleanup} initiates a cleanup of the any client-specific state as maintained by a managed
     * connection instance. The cleanup should invalidate all connection handles that had been created using this
     * managed connection instance. Any attempt by an application component to use the connection handle after cleanup
     * of the underlying managed connection should result in an exception.
     * </p>
     * <p>
     * The cleanup of managed connection is always driven by an application server. An application server should not
     * invoke {@code cleanup} when there is an uncompleted transaction (associated with a managed connection instance)
     * in progress.
     * </p>
     * <p>
     * The invocation of the {@code cleanup} method on an already cleaned-up connection should not throw an exception.
     * </p>
     * <p>
     * The cleanup of a managed connection instance resets its client specific state and prepares the connection to be
     * put back in to a connection pool. The cleanup method should not cause resource adapter to close the physical pipe
     * and reclaim system resources associated with the physical connection.
     * </p>
     *
     * @throws SQLException
     *         generic exception if operation fails
     */
    // TODO Consider removing (though might be used to implement XADataSource/ConnectionPoolDataSource without proxies)
    public void cleanup() throws SQLException {
        synchronized (syncObject) {
            disassociateConnections();

            getGDSHelper().setCurrentTransaction(null);

            // reset the transaction mapping to use the default of the MCF
            transactionMapping = null;
            // reset the TPB from the previous transaction.
            tpb = mcf.getDefaultTpb();
            transactionIsolation = mcf.getDefaultTransactionIsolation();
        }
    }

    /**
     * Disassociate connections from current managed connection.
     */
    private void disassociateConnections() throws SQLException {
        final FBConnection connection = connectionHandle;
        if (connection != null) {
            connection.close();
        }
    }

    /**
     * Disassociate connections without cleanly closing them.
     */
    private void forceDisassociateConnections() {
        final FBConnection connection = connectionHandleUpdater.getAndSet(this, null);
        if (connection != null) {
            try {
                connection.setManagedConnection(null);
                connection.close();
            } catch (SQLException sqlex) {
                log.debug("Exception ignored during forced disassociation", sqlex);
            }
        }
    }

    /**
     * Creates a new connection handle for the underlying physical connection represented by the managed connection
     * instance. This connection handle is used by the application code to refer to the underlying physical connection.
     *
     * @return instance representing the connection handle
     * @throws SQLException
     *         generic exception if operation fails
     */
    public FBConnection getConnection() throws SQLException {
        disassociateConnections();

        FBConnection c = mcf.newConnection(this);
        c.setManagedEnvironment(isManagedEnvironment());
        FBConnection previous = connectionHandleUpdater.getAndSet(this, c);
        if (previous != null) {
            previous.setManagedConnection(null);
            if (log.isDebugEnabled()) {
                // This would indicate a concurrent getConnection call on this managed connection
                log.debug("A connection was already associated with the managed connection",
                        new RuntimeException("debug call trace"));
            }
            try {
                previous.setManagedConnection(null);
                previous.close();
            } catch (SQLException e) {
                log.debug("Error forcing previous connection to close", e);
            }
        }
        final SQLWarning warnings = unnotifiedWarningsUpdater.getAndSet(this, null);
        if (warnings != null) {
            c.addWarning(warnings);
        }
        return c;
    }

    /**
     * Destroys the physical connection to the underlying resource manager.
     * <p>
     * To manage the size of the connection pool, an application server can explicitly call {@code destroy} to destroy
     * a physical connection. A resource adapter should destroy all allocated system resources for this managed
     * connection instance when the method destroy is called.
     * </p>
     *
     * @throws SQLException
     *         generic exception if operation failed
     */
    public void destroy() throws SQLException {
        destroy(null);
    }

    public void destroy(XcaConnectionEvent connectionEvent) throws SQLException {
        if (gdsHelper == null) {
            return;
        }

        try {
            if (isBrokenConnection(connectionEvent)) {
                FbDatabase currentDatabase = gdsHelper.getCurrentDatabase();
                currentDatabase.forceClose();
            } else {
                if (inTransaction()) {
                    // TODO More specific exception, Jaybird error code
                    // TODO should we skip disassociation in this case?
                    throw new SQLException("Can't destroy managed connection with active transaction");
                }

                gdsHelper.detachDatabase();
            }
        } finally {
            gdsHelper = null;
            forceDisassociateConnections();
        }
    }

    private boolean isBrokenConnection(XcaConnectionEvent connectionEvent) {
        if (connectionEvent == null
                || connectionEvent.getEventType() != XcaConnectionEvent.EventType.CONNECTION_ERROR_OCCURRED) {
            return false;
        }

        Exception connectionEventException = connectionEvent.getException();
        if (connectionEventException == null) {
            return false;
        }

        SQLException firstSqlException = findException(connectionEventException, SQLException.class);
        if (firstSqlException != null && isBrokenConnectionErrorCode(firstSqlException.getErrorCode())) {
            return true;
        }

        if (findException(connectionEventException, SocketTimeoutException.class) != null) {
            return true;
        }

        // TODO Should this test for SocketException or something else, as SocketTimeoutException is also tested in the
        //  previous check
        //noinspection RedundantIfStatement
        if (findException(connectionEventException, SocketTimeoutException.class) != null) {
            return true;
        }

        return false;
    }

    private boolean isBrokenConnectionErrorCode(int iscCode) {
        return iscCode == ISCConstants.isc_network_error
                || iscCode == ISCConstants.isc_net_read_err
                || iscCode == ISCConstants.isc_net_write_err;
    }

    private <T extends Exception> T findException(Exception root, Class<T> exceptionType) {
        Throwable current = root;
        while (current != null) {
            if (exceptionType.isInstance(current)) {
                return exceptionType.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * Returns an {@code javax.transaction.xa.XAresource} instance. An application server enlists this XAResource
     * instance with the Transaction Manager if the FBManagedConnection instance is being used in a Java EE transaction
     * that is coordinated by the Transaction Manager.
     *
     * @return XAResource instance
     */
    public XAResource getXAResource() {
        log.debug("XAResource requested from FBManagedConnection");
        synchronized (syncObject) {
            if (xaResource == null) {
                xaResource = new FbMcXaResource();
            }
            return xaResource;
        }
    }

    // --------------------------------------------------------------
    // XAResource implementation
    // The actual XAResource is exposed using the inner class FbMcXaResource
    // --------------------------------------------------------------

    // TODO validate correctness of state set
    private static final Set<TransactionState> XID_ACTIVE_STATE = Collections.unmodifiableSet(EnumSet.of(TransactionState.ACTIVE, TransactionState.PREPARED, TransactionState.PREPARING));

    boolean isXidActive(Xid xid) {
        FbTransaction transaction = xidMap.get(xid);
        return transaction != null && XID_ACTIVE_STATE.contains(transaction.getState());
    }

    private void commit(Xid id, boolean onePhase) throws XAException {
        mcf.notifyCommit(this, id, onePhase);
    }

    /**
     * The {@code internalCommit} method performs the requested commit and may throw an XAException to be interpreted
     * by the caller.
     *
     * @param xid
     *         a {@code Xid} value
     * @param onePhase
     *         a {@code true} if this is not a two-phase commit (not a distributed transaction)
     * @throws XAException
     *         if an error occurs
     */
    void internalCommit(Xid xid, boolean onePhase) throws XAException {
        if (log.isTraceEnabled()) log.trace("Commit called: " + xid);
        FbTransaction committingTr = xidMap.get(xid);

        // check that prepare has NOT been called when onePhase = true
        if (onePhase && isPrepared(xid)) {
            throw new FBXAException("Cannot commit one-phase when transaction has been prepared", XAException.XAER_PROTO);
        }

        // check that prepare has been called when onePhase = false
        if (!onePhase && !isPrepared(xid)) {
            throw new FBXAException("Cannot commit two-phase when transaction has not been prepared", XAException.XAER_PROTO);
        }

        if (committingTr == null) {
            throw new FBXAException("Commit called with unknown transaction", XAException.XAER_NOTA);
        }

        try {
            if (committingTr == getGDSHelper().getCurrentTransaction()) {
                throw new FBXAException("Commit called with non-ended xid", XAException.XAER_PROTO);
            }

            committingTr.commit();
        } catch (SQLException ge) {
            if (gdsHelper != null) {
                try {
                    committingTr.rollback();
                } catch (SQLException ge2) {
                    log.debug("Exception rolling back failed tx: ", ge2);
                }
            } else {
                log.warn("Unable to rollback failed tx, connection closed or lost");
            }
            throw new FBXAException(ge.getMessage(), XAException.XAER_RMERR, ge);
        } finally {
            xidMap.remove(xid);
            preparedXid.remove(xid);
        }
    }

    private boolean isPrepared(Xid xid) {
        return preparedXid.contains(xid);
    }

    /**
     * Dissociates a resource from a global transaction.
     *
     * @throws XAException
     *         Occurs when the state was not correct (end called twice), or the transaction ID is wrong.
     */
    private void end(Xid id, int flags) throws XAException {
        if (flags != XAResource.TMSUCCESS && flags != XAResource.TMFAIL && flags != XAResource.TMSUSPEND)
            throw new FBXAException("flag not allowed in this context: " + flags + ", valid flags are TMSUCCESS, TMFAIL, TMSUSPEND", XAException.XAER_PROTO);
        internalEnd(id, flags);
        mcf.notifyEnd(this, id);
        inDistributedTransaction = false;

        try {
            // This will reset the managed environment of the associated connections and set the transaction coordinator to local
            // TODO This is a bit of a hack; need to find a better way
            setManagedEnvironment(isManagedEnvironment());
        } catch (SQLException ex) {
            throw new FBXAException("Reset of managed state failed", XAException.XAER_RMERR, ex);
        }
    }

    /**
     * The {@code internalEnd} method ends the xid as requested if appropriate and throws a XAException including the
     * appropriate XA error code and a message if not. The caller can decode the exception as necessary.
     *
     * @param xid
     *         a {@code Xid} value
     * @param flags
     *         an {@code int} value
     * @throws XAException
     *         if an error occurs
     */
    void internalEnd(Xid xid, int flags) throws XAException {
        if (log.isDebugEnabled()) log.debug("End called: " + xid);
        FbTransaction endingTr = xidMap.get(xid);

        if (endingTr == null) {
            throw new FBXAException("Unrecognized transaction", XAException.XAER_NOTA);
        }

        if (flags == XAResource.TMFAIL) {
            try {
                endingTr.rollback();
                getGDSHelper().setCurrentTransaction(null);
            } catch (SQLException ex) {
                throw new FBXAException("can't rollback transaction", XAException.XAER_RMFAIL, ex);
            }
        } else if (flags == XAResource.TMSUCCESS) {
            if (gdsHelper != null && endingTr == gdsHelper.getCurrentTransaction()) {
                gdsHelper.setCurrentTransaction(null);
            } else {
                throw new FBXAException("You are trying to end a transaction that is not the current transaction",
                        XAException.XAER_INVAL);
            }
        } else if (flags == XAResource.TMSUSPEND) {
            if (gdsHelper != null && endingTr == gdsHelper.getCurrentTransaction()) {
                gdsHelper.setCurrentTransaction(null);
            } else {
                throw new FBXAException("You are trying to suspend a transaction that is not the current transaction",
                        XAException.XAER_INVAL);
            }
        }
    }

    private final static String FORGET_FIND_QUERY = "SELECT RDB$TRANSACTION_ID, RDB$TRANSACTION_DESCRIPTION "
            + "FROM RDB$TRANSACTIONS WHERE RDB$TRANSACTION_STATE IN (2, 3)";
    private final static String FORGET_DELETE_QUERY = "DELETE FROM RDB$TRANSACTIONS WHERE RDB$TRANSACTION_ID = ";

    /**
     * Indicates that no further action will be taken on behalf of this
     * transaction (after a heuristic failure). It is assumed this will be
     * called after a failed commit or rollback.
     *
     * @throws XAException
     *         Occurs when the state was not correct (end never called), or the transaction ID is wrong.
     */
    private void forget(Xid id) throws XAException {
        // TODO Should this method call FBManagedConnectionFactory.forget?
        long inLimboId = -1;

        try {
            // find XID
            // TODO: Is there a reason why this piece of code can't use the JDBC Statement class?
            FbTransaction trHandle2 = database.startTransaction(tpb.getTransactionParameterBuffer());
            FbStatement stmtHandle2 = database.createStatement(trHandle2);

            GDSHelper gdsHelper2 = new GDSHelper(database);
            gdsHelper2.setCurrentTransaction(trHandle2);

            stmtHandle2.prepare(FORGET_FIND_QUERY);

            DataProvider dataProvider0 = new DataProvider(0);
            stmtHandle2.addStatementListener(dataProvider0);
            DataProvider dataProvider1 = new DataProvider(1);
            stmtHandle2.addStatementListener(dataProvider1);

            stmtHandle2.execute(RowValue.EMPTY_ROW_VALUE);
            stmtHandle2.fetchRows(10);

            FBField field0 = FBField.createField(stmtHandle2.getRowDescriptor().getFieldDescriptor(0), dataProvider0, gdsHelper2, false);
            FBField field1 = FBField.createField(stmtHandle2.getRowDescriptor().getFieldDescriptor(1), dataProvider1, gdsHelper2, false);

            int row = 0;
            while (row < dataProvider0.getRowCount()) {
                dataProvider0.setRow(row);
                dataProvider1.setRow(row);

                long inLimboTxId = field0.getLong();
                byte[] inLimboMessage = field1.getBytes();

                try {
                    FBXid xid = new FBXid(new ByteArrayInputStream(inLimboMessage), inLimboTxId);

                    boolean gtridEquals = Arrays.equals(xid.getGlobalTransactionId(), id.getGlobalTransactionId());
                    boolean bqualEquals = Arrays.equals(xid.getBranchQualifier(), id.getBranchQualifier());

                    if (gtridEquals && bqualEquals) {
                        inLimboId = inLimboTxId;
                        break;
                    }
                } catch (FBIncorrectXidException ex) {
                    String message = "incorrect XID format in RDB$TRANSACTIONS where RDB$TRANSACTION_ID=" + inLimboTxId;
                    log.warn(message + ": " + ex + "; see debug level for stacktrace");
                    log.debug(message, ex);
                }

                row++;
            }

            stmtHandle2.close();
            trHandle2.commit();
        } catch (SQLException | IOException ex) {
            log.debug("can't perform query to fetch xids", ex);
            throw new FBXAException(XAException.XAER_RMFAIL, ex);
        }

        if (inLimboId == -1) {
            throw new FBXAException("XID not found", XAException.XAER_NOTA); // TODO: is XAER_NOTA the proper error code ?
        }

        try {
            // delete XID

            FbTransaction trHandle2 = database.startTransaction(tpb.getTransactionParameterBuffer());

            FbStatement stmtHandle2 = database.createStatement(trHandle2);

            stmtHandle2.prepare(FORGET_DELETE_QUERY + inLimboId);
            stmtHandle2.execute(RowValue.EMPTY_ROW_VALUE);

            stmtHandle2.close();
            trHandle2.commit();
        } catch (SQLException ex) {
            throw new FBXAException("can't perform query to fetch xids", XAException.XAER_RMFAIL, ex);
        }
    }

    private int getTransactionTimeout() throws XAException {
        return timeout;
    }

    /**
     * Prepares a transaction to commit.
     *
     * @throws XAException
     *         Occurs when the state was not correct (end never called), the transaction ID is wrong, or the connection
     *         was set to Auto-Commit.
     */
    private int prepare(Xid xid) throws XAException {
        return mcf.notifyPrepare(this, xid);
    }

    int internalPrepare(Xid xid) throws FBXAException {
        if (log.isTraceEnabled()) log.trace("prepare called: " + xid);
        FbTransaction committingTr = xidMap.get(xid);
        if (committingTr == null) {
            throw new FBXAException("Prepare called with unknown transaction", XAException.XAER_NOTA);
        }
        try {
            if (committingTr == getGDSHelper().getCurrentTransaction()) {
                throw new FBXAException("Prepare called with non-ended xid", XAException.XAER_PROTO);
            }

            FBXid fbxid;
            if (xid instanceof FBXid) {
                fbxid = (FBXid) xid;
            } else {
                fbxid = new FBXid(xid);
            }
            byte[] message = fbxid.toBytes();

            committingTr.prepare(message);
        } catch (SQLException ge) {
            try {
                if (gdsHelper != null) {
                    committingTr.rollback();
                } else {
                    log.warn("Unable to rollback failed tx, connection closed or lost");
                }
            } catch (SQLException ge2) {
                log.debug("Exception rolling back failed tx: ", ge2);
            } finally {
                xidMap.remove(xid);
            }

            log.warn("error in prepare", ge);
            throw new FBXAException(XAException.XAER_RMERR, ge);
        }

        preparedXid.add(xid);
        return XAResource.XA_OK;
    }

    private static final String RECOVERY_QUERY = "SELECT RDB$TRANSACTION_ID, RDB$TRANSACTION_DESCRIPTION "
            + "FROM RDB$TRANSACTIONS";

    /**
     * Obtain a list of prepared transaction branches from a resource manager.
     * The transaction manager calls this method during recovery to obtain the
     * list of transaction branches that are currently in prepared or
     * heuristically completed states.
     *
     * @param flags
     *         One of TMSTARTRSCAN, TMENDRSCAN, TMNOFLAGS. TMNOFLAGS must be used when no other flags are set in flags.
     * @return The resource manager returns zero or more XIDs for the transaction branches that are currently in a
     * prepared or heuristically completed state. If an error occurs during the operation, the resource manager should
     * throw the appropriate XAException.
     * @throws XAException
     *         An error has occurred. Possible values are XAER_RMERR, XAER_RMFAIL, XAER_INVAL, and XAER_PROTO.
     */
    private Xid[] recover(int flags) throws javax.transaction.xa.XAException {
        if (flags != XAResource.TMSTARTRSCAN && flags != XAResource.TMENDRSCAN && flags != XAResource.TMNOFLAGS
                && flags != (XAResource.TMSTARTRSCAN | XAResource.TMENDRSCAN)) {
            throw new FBXAException("flag not allowed in this context: " + flags + ", valid flags are TMSTARTRSCAN, TMENDRSCAN, TMNOFLAGS, TMSTARTRSCAN|TMENDRSCAN", XAException.XAER_PROTO);
        }

        try {
            // if (!((flags & XAResource.TMSTARTRSCAN) == 0))
//            if ((flags & XAResource.TMENDRSCAN) == 0 && (flags & XAResource.TMNOFLAGS) == 0)
//                return new Xid[0];

            List<FBXid> xids = new ArrayList<>();

            FbTransaction trHandle2 = database.startTransaction(tpb.getTransactionParameterBuffer());

            FbStatement stmtHandle2 = database.createStatement(trHandle2);

            GDSHelper gdsHelper2 = new GDSHelper(database);
            gdsHelper2.setCurrentTransaction(trHandle2);

            stmtHandle2.prepare(RECOVERY_QUERY);

            DataProvider dataProvider0 = new DataProvider(0);
            stmtHandle2.addStatementListener(dataProvider0);
            DataProvider dataProvider1 = new DataProvider(1);
            stmtHandle2.addStatementListener(dataProvider1);

            stmtHandle2.execute(RowValue.EMPTY_ROW_VALUE);
            stmtHandle2.fetchRows(10);

            FBField field0 = FBField.createField(stmtHandle2.getRowDescriptor().getFieldDescriptor(0), dataProvider0, gdsHelper2, false);
            FBField field1 = FBField.createField(stmtHandle2.getRowDescriptor().getFieldDescriptor(1), dataProvider1, gdsHelper2, false);

            int row = 0;
            while (row < dataProvider0.getRowCount()) {
                dataProvider0.setRow(row);
                dataProvider1.setRow(row);

                long inLimboTxId = field0.getLong();
                byte[] inLimboMessage = field1.getBytes();

                try {
                    FBXid xid = new FBXid(new ByteArrayInputStream(inLimboMessage), inLimboTxId);
                    xids.add(xid);
                } catch (FBIncorrectXidException ex) {
                    log.warn("ignoring XID stored with invalid format in RDB$TRANSACTIONS for RDB$TRANSACTION_ID=" + inLimboTxId);
                }

                row++;
            }

            stmtHandle2.close();
            trHandle2.commit();

            return xids.toArray(new FBXid[0]);
        } catch (SQLException | IOException e) {
            throw new FBXAException("can't perform query to fetch xids", XAException.XAER_RMFAIL, e);
        }
    }

    private static final String RECOVERY_QUERY_PARAMETRIZED =
            "SELECT RDB$TRANSACTION_ID, RDB$TRANSACTION_DESCRIPTION "
                    + "FROM RDB$TRANSACTIONS "
                    + "WHERE RDB$TRANSACTION_DESCRIPTION = CAST(? AS VARCHAR(32764) CHARACTER SET OCTETS)";

    /**
     * Obtain a single prepared transaction branch from a resource manager, based on a Xid
     *
     * @param externalXid
     *         The Xid to find
     * @return The Xid if found, otherwise null.
     * @throws XAException
     *         An error has occurred. Possible values are XAER_RMERR,
     *         XAER_RMFAIL, XAER_INVAL, and XAER_PROTO.
     */
    protected Xid findSingleXid(Xid externalXid) throws javax.transaction.xa.XAException {
        try {
            FbTransaction trHandle2 = database.startTransaction(tpb.getTransactionParameterBuffer());

            FbStatement stmtHandle2 = database.createStatement(trHandle2);

            GDSHelper gdsHelper2 = new GDSHelper(database);
            gdsHelper2.setCurrentTransaction(trHandle2);

            stmtHandle2.prepare(RECOVERY_QUERY_PARAMETRIZED);

            DataProvider dataProvider0 = new DataProvider(0);
            stmtHandle2.addStatementListener(dataProvider0);
            DataProvider dataProvider1 = new DataProvider(1);
            stmtHandle2.addStatementListener(dataProvider1);

            FBXid tempXid = new FBXid(externalXid);
            final RowValue parameters = RowValue.of(stmtHandle2.getParameterDescriptor(),
                    tempXid.toBytes());
            stmtHandle2.execute(parameters);
            stmtHandle2.fetchRows(1);

            FBField field0 = FBField.createField(stmtHandle2.getRowDescriptor().getFieldDescriptor(0), dataProvider0, gdsHelper2, false);
            FBField field1 = FBField.createField(stmtHandle2.getRowDescriptor().getFieldDescriptor(1), dataProvider1, gdsHelper2, false);

            FBXid xid = null;
            if (dataProvider0.getRowCount() > 0) {
                dataProvider0.setRow(0);
                dataProvider1.setRow(0);

                long inLimboTxId = field0.getLong();
                byte[] inLimboMessage = field1.getBytes();

                try {
                    xid = new FBXid(new ByteArrayInputStream(inLimboMessage), inLimboTxId);
                } catch (FBIncorrectXidException ex) {
                    log.warn("ignoring XID stored with invalid format in RDB$TRANSACTIONS for RDB$TRANSACTION_ID=" + inLimboTxId);
                }
            }

            stmtHandle2.close();
            trHandle2.commit();

            return xid;
        } catch (SQLException | IOException e) {
            throw new FBXAException("can't perform query to fetch xids", XAException.XAER_RMFAIL, e);
        }
    }

    @Override
    public final Object getSynchronizationObject() {
        return syncObject;
    }

    private static class DataProvider extends DefaultStatementListener implements FieldDataProvider {
        private final List<RowValue> rows = new ArrayList<>();
        private final int fieldPos;
        private int row;

        private DataProvider(int fieldPos) {
            this.fieldPos = fieldPos;
        }

        public void setRow(int row) {
            this.row = row;
        }

        public byte[] getFieldData() {
            return rows.get(row).getFieldData(fieldPos);
        }

        public void setFieldData(byte[] data) {
            throw new UnsupportedOperationException();
        }

        public int getRowCount() {
            return rows.size();
        }

        @Override
        public void receivedRow(FbStatement sender, RowValue rowValue) {
            rows.add(rowValue);
        }
    }

    /**
     * Rolls back the work, assuming it was done on behalf of the specified
     * transaction.
     *
     * @throws XAException
     *         Occurs when the state was not correct (end never called), the transaction ID is wrong, the connection
     *         was set to Auto-Commit, or the rollback on the underlying connection fails. The error code differs
     *         depending on the exact situation.
     */
    private void rollback(Xid xid) throws XAException {
        mcf.notifyRollback(this, xid);
    }

    void internalRollback(Xid xid) throws XAException {
        if (log.isTraceEnabled()) log.trace("rollback called: " + xid);
        FbTransaction committingTr = xidMap.get(xid);
        if (committingTr == null) {
            throw new FBXAException("Rollback called with unknown transaction: " + xid);
        }

        try {
            if (committingTr == getGDSHelper().getCurrentTransaction())
                throw new FBXAException("Rollback called with non-ended xid", XAException.XAER_PROTO);

            try {
                committingTr.rollback();
            } finally {
                xidMap.remove(xid);
                preparedXid.remove(xid);
            }
        } catch (SQLException ge) {
            log.debug("Exception in rollback", ge);
            throw new FBXAException(ge.getMessage(), XAException.XAER_RMERR, ge);
        }
    }

    /**
     * Sets the transaction timeout. This is saved, but the value is not used by
     * the current implementation.
     *
     * @param timeout
     *         The timeout to be set in seconds
     */
    private boolean setTransactionTimeout(int timeout) throws XAException {
        this.timeout = timeout;
        return true;
    }

    public boolean inDistributedTransaction() {
        return inDistributedTransaction;
    }

    /**
     * Associates a JDBC connection with a global transaction. We assume that
     * end will be called followed by prepare, commit, or rollback. If start is
     * called after end but before commit or rollback, there is no way to
     * distinguish work done by different transactions on the same connection).
     * If start is called more than once before end, either it's a duplicate
     * transaction ID or illegal transaction ID (since you can't have two
     * transactions associated with one DB connection).
     *
     * @param id
     *         A global transaction identifier to be associated with the resource
     * @param flags
     *         One of TMNOFLAGS, TMJOIN, or TMRESUME
     * @throws XAException
     *         Occurs when the state was not correct (start called twice), the transaction ID is wrong, or the instance
     *         has already been closed.
     */
    private void start(Xid id, int flags) throws XAException {
        if (flags != XAResource.TMNOFLAGS && flags != XAResource.TMJOIN && flags != XAResource.TMRESUME) {
            throw new FBXAException("flag not allowed in this context: " + flags + ", valid flags are TMNOFLAGS, TMJOIN, TMRESUME", XAException.XAER_PROTO);
        }
        if (flags == XAResource.TMJOIN) {
            throw new FBXAException("Joining two transactions is not supported", XAException.XAER_RMFAIL);
        }

        try {
            // reset the transaction parameters for the managed scenario
            setTransactionIsolation(mcf.getDefaultTransactionIsolation());

            internalStart(id, flags);

            mcf.notifyStart(this, id);

            inDistributedTransaction = true;

            // This will reset the managed environment of the associated connections and set the transaction coordinator to managed
            // TODO This is a bit of a hack; need to find a better way
            setManagedEnvironment(isManagedEnvironment());

        } catch (SQLException e) {
            throw new FBXAException(XAException.XAER_RMERR, e);
        }
    }

    /**
     * Perform the internal processing to start associate a JDBC connection with
     * a global transaction.
     *
     * @param id
     *         A global transaction identifier to be associated with the resource
     * @param flags
     *         One of TMNOFLAGS, TMJOIN, or TMRESUME
     * @throws XAException
     *         If the transaction is already started, or this connection cannot participate in the distributed
     *         transaction
     * @throws SQLException
     * @see #start(Xid, int)
     */
    public void internalStart(Xid id, int flags) throws XAException, SQLException {
        if (log.isTraceEnabled()) log.trace("start called: " + id);

        if (getGDSHelper().getCurrentTransaction() != null)
            throw new FBXAException("Transaction already started", XAException.XAER_PROTO);

        findIscTrHandle(id, flags);
    }

    // FB public methods. Could be package if packages reorganized.

    /**
     * Close this connection with regards to a wrapping {@code AbstractConnection}.
     *
     * @param c
     *         The {@code AbstractConnection} that is being closed
     */
    public void close(FBConnection c) {
        c.setManagedConnection(null);
        if (!connectionHandleUpdater.compareAndSet(this, c, null) && log.isDebugEnabled()) {
            log.debug("Call of close for connection not currently associated with this managed connection",
                    new RuntimeException("debug call trace"));
        }
        XcaConnectionEvent ce = new XcaConnectionEvent(this, XcaConnectionEvent.EventType.CONNECTION_CLOSED);
        ce.setConnectionHandle(c);
        notify(connectionClosedNotifier, ce);
    }

    /**
     * Get information about the current connection parameters.
     *
     * @return instance of {@link FBConnectionRequestInfo}.
     */
    public FBConnectionRequestInfo getConnectionRequestInfo() {
        return cri;
    }

    public TransactionParameterBuffer getTransactionParameters() {
        synchronized (syncObject) {
            return tpb.getTransactionParameterBuffer();
        }
    }

    public void setTransactionParameters(TransactionParameterBuffer transactionParameters) {
        synchronized (syncObject) {
            tpb.setTransactionParameterBuffer(transactionParameters);
        }
    }

    public TransactionParameterBuffer getTransactionParameters(int isolation) {
        synchronized (syncObject) {
            final FBTpbMapper mapping = transactionMapping;
            if (mapping == null) {
                return mcf.getTransactionParameters(isolation);
            }
            return mapping.getMapping(isolation);
        }
    }

    public void setTransactionParameters(int isolation, TransactionParameterBuffer transactionParams)
            throws SQLException {
        synchronized (syncObject) {
            FBTpbMapper mapping = transactionMapping;
            if (mapping == null) {
                mapping = transactionMapping = mcf.getTransactionMappingCopy();
            }
            mapping.setMapping(isolation, transactionParams);
            if (getTransactionIsolation() == isolation) {
                // Make sure next transaction uses the new config
                setTransactionIsolation(isolation);
            }
        }
    }

    private void findIscTrHandle(Xid xid, int flags) throws SQLException, XAException {
        // FIXME return old tr handle if it is still valid before proceeding
        getGDSHelper().setCurrentTransaction(null);

        if (flags == XAResource.TMRESUME) {
            FbTransaction trHandle = xidMap.get(xid);
            if (trHandle == null) {
                throw new FBXAException(
                        "You are trying to resume a transaction that is not attached to this XAResource",
                        XAException.XAER_INVAL);
            }

            getGDSHelper().setCurrentTransaction(trHandle);
            return;
        }

        for (Xid knownXid : xidMap.keySet()) {
            boolean sameFormatId = knownXid.getFormatId() == xid.getFormatId();
            boolean sameGtrid = Arrays.equals(knownXid.getGlobalTransactionId(), xid.getGlobalTransactionId());
            boolean sameBqual = Arrays.equals(knownXid.getBranchQualifier(), xid.getBranchQualifier());
            if (sameFormatId && sameGtrid && sameBqual)
                throw new FBXAException(
                        "A transaction with the same XID has already been started",
                        XAException.XAER_DUPID);
        }

        // new xid for us
        try {
            FbTransaction transaction = getGDSHelper().startTransaction(tpb.getTransactionParameterBuffer());
            xidMap.put(xid, transaction);
        } catch (SQLException e) {
            throw new FBXAException(e.getMessage(), XAException.XAER_RMERR, e);
        }
    }

    void notify(CELNotifier notifier, XcaConnectionEvent ce) {
        for (XcaConnectionEventListener cel : connectionEventListeners) {
            notifier.notify(cel, ce);
        }
    }

    @FunctionalInterface
    interface CELNotifier {
        void notify(XcaConnectionEventListener cel, XcaConnectionEvent ce);
    }

    static final CELNotifier connectionClosedNotifier = XcaConnectionEventListener::connectionClosed;
    static final CELNotifier connectionErrorOccurredNotifier = XcaConnectionEventListener::connectionErrorOccurred;

    /**
     * Get the transaction isolation level of this connection. The level is one of the static final fields of
     * {@code java.sql.Connection} (i.e. {@code TRANSACTION_READ_COMMITTED}, {@code TRANSACTION_READ_UNCOMMITTED},
     * {@code TRANSACTION_REPEATABLE_READ}, {@code TRANSACTION_SERIALIZABLE}.
     *
     * @return Value representing a transaction isolation level defined in {@link java.sql.Connection}.
     * @throws SQLException
     *         If the transaction level cannot be retrieved
     * @see java.sql.Connection
     * @see #setTransactionIsolation(int)
     */
    public int getTransactionIsolation() throws SQLException {
        synchronized (syncObject) {
            return transactionIsolation;
        }
    }

    /**
     * Set the transaction level for this connection. The level is one of the static final fields of
     * {@code java.sql.Connection} (i.e. {@code TRANSACTION_READ_COMMITTED}, {@code TRANSACTION_READ_UNCOMMITTED},
     * {@code TRANSACTION_REPEATABLE_READ}, {@code TRANSACTION_SERIALIZABLE}.
     *
     * @param isolation
     *         Value representing a transaction isolation level defined in {@link java.sql.Connection}.
     * @throws SQLException
     *         If the transaction level cannot be retrieved
     * @see java.sql.Connection
     * @see #getTransactionIsolation()
     */
    public void setTransactionIsolation(int isolation) throws SQLException {
        synchronized (syncObject) {
            transactionIsolation = isolation;
            final FBTpbMapper mapping = transactionMapping;
            tpb = mapping == null
                    ? mcf.getTpb(isolation)
                    : new FBTpb(mapping.getMapping(isolation));
        }
    }

    /**
     * Get the managed connection factory that created this managed connection.
     *
     * @return instance of {@link FBManagedConnectionFactory}.
     */
    public FBManagedConnectionFactory getManagedConnectionFactory() {
        return mcf;
    }

    /**
     * Set whether this connection is to be readonly
     *
     * @param readOnly
     *         If {@code true}, the connection will be set read-only, otherwise it will be writable
     */
    public void setReadOnly(boolean readOnly) {
        tpb.setReadOnly(readOnly);
    }

    /**
     * Retrieve whether this connection is readonly.
     *
     * @return {@code true} if this connection is readonly, {@code false} otherwise
     */
    public boolean isReadOnly() {
        return tpb.isReadOnly();
    }

    private void notifyWarning(SQLWarning warning) {
        final FBConnection connection = connectionHandle;
        if (connection == null) {
            while (true) {
                if (!unnotifiedWarningsUpdater.compareAndSet(this, null, warning)) {
                    final SQLWarning warnings = unnotifiedWarnings;
                    if (warnings == null) {
                        continue;
                    }
                    warnings.setNextWarning(warning);
                }
                break;
            }
        } else {
            final SQLWarning warnings = unnotifiedWarningsUpdater.getAndSet(this, null);
            if (warnings != null) {
                warnings.setNextWarning(warning);
                warning = warnings;
            }
            connection.addWarning(warning);
        }
    }

    private static String getDefaultConnectionEncoding() {
        try {
            String defaultConnectionEncoding = JaybirdSystemProperties.getDefaultConnectionEncoding();
            if (defaultConnectionEncoding == null) {
                if (JaybirdSystemProperties.isRequireConnectionEncoding()) {
                    return null;
                }
                return "NONE";
            }
            return defaultConnectionEncoding;
        } catch (Exception e) {
            log.error("Exception obtaining default connection encoding", e);
        }
        return "NONE";
    }

    /**
     * DatabaseListener implementation for use by this managed connection.
     */
    private class MCDatabaseListener extends DefaultDatabaseListener {
        @Override
        public void warningReceived(FbDatabase database, SQLWarning warning) {
            if (database != FBManagedConnection.this.database) {
                database.removeDatabaseListener(this);
                return;
            }
            notifyWarning(warning);
        }
    }

    /**
     * XAResource implementation that delegates to the managed connection itself.
     */
    private final class FbMcXaResource implements XAResource {

        private FBManagedConnection getMc() {
            return FBManagedConnection.this;
        }

        @Override
        public void start(Xid xid, int flags) throws XAException {
            FBManagedConnection.this.start(xid, flags);
        }

        @Override
        public int prepare(Xid xid) throws XAException {
            return FBManagedConnection.this.prepare(xid);
        }

        @Override
        public void commit(Xid xid, boolean onePhase) throws XAException {
            FBManagedConnection.this.commit(xid, onePhase);
        }

        @Override
        public void rollback(Xid xid) throws XAException {
            FBManagedConnection.this.rollback(xid);
        }

        @Override
        public void end(Xid xid, int flags) throws XAException {
            FBManagedConnection.this.end(xid, flags);
        }

        @Override
        public void forget(Xid xid) throws XAException {
            FBManagedConnection.this.forget(xid);
        }

        @Override
        public Xid[] recover(int flag) throws XAException {
            return FBManagedConnection.this.recover(flag);
        }

        /**
         * Retrieve whether this {@code XAResource} uses the same ResourceManager as {@code res}. This method relies on
         * {@code res} being a Firebird implementation of {@code XAResource}.
         *
         * @param res
         *         The other {@code XAResource} to compare to
         * @return {@code true} if {@code res} uses the same ResourceManager, {@code false} otherwise
         */
        @Override
        public boolean isSameRM(XAResource res) throws XAException {
            return res == this
                    || res instanceof FbMcXaResource && database == ((FbMcXaResource) res).getMc().database;
        }

        @Override
        public int getTransactionTimeout() throws XAException {
            return FBManagedConnection.this.getTransactionTimeout();
        }

        @Override
        public boolean setTransactionTimeout(int seconds) throws XAException {
            return FBManagedConnection.this.setTransactionTimeout(seconds);
        }
    }
}
