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
package org.firebirdsql.gds.ng.jna;

import com.sun.jna.ptr.IntByReference;
import org.firebirdsql.gds.ng.AbstractFbTransaction;
import org.firebirdsql.gds.ng.TransactionState;
import org.firebirdsql.jna.fbclient.FbClientLibrary;
import org.firebirdsql.jna.fbclient.ISC_STATUS;
import org.firebirdsql.logging.Logger;
import org.firebirdsql.logging.LoggerFactory;

import java.nio.ByteBuffer;
import java.sql.SQLException;

/**
 * Implementation of {@link org.firebirdsql.gds.ng.FbTransaction} for native client access.
 *
 * @author <a href="mailto:mrotteveel@users.sourceforge.net">Mark Rotteveel</a>
 * @since 3.0
 */
public class JnaTransaction extends AbstractFbTransaction {

    private static final Logger log = LoggerFactory.getLogger(JnaTransaction.class);

    private final IntByReference handle;
    private final ISC_STATUS[] statusVector = new ISC_STATUS[JnaDatabase.STATUS_VECTOR_SIZE];
    private final FbClientLibrary clientLibrary;

    /**
     * Initializes AbstractFbTransaction.
     *
     * @param database
     *         FbDatabase that created this handle.
     * @param transactionHandle
     *         Transaction handle
     * @param initialState
     *         Initial transaction state (allowed values are {@link org.firebirdsql.gds.ng.TransactionState#ACTIVE}
     *         and {@link org.firebirdsql.gds.ng.TransactionState#PREPARED}.
     */
    public JnaTransaction(JnaDatabase database, IntByReference transactionHandle, TransactionState initialState) {
        super(initialState, database);
        handle = transactionHandle;
        clientLibrary = database.getClientLibrary();
    }

    @Override
    public JnaDatabase getDatabase() {
        return (JnaDatabase) super.getDatabase();
    }

    @Override
    public int getHandle() {
        return handle.getValue();
    }

    public IntByReference getJnaHandle() {
        return handle;
    }

    @Override
    public void commit() throws SQLException {
        try {
            synchronized (getSynchronizationObject()) {
                final JnaDatabase db = getDatabase();
                db.checkConnected();
                switchState(TransactionState.COMMITTING);
                clientLibrary.isc_commit_transaction(statusVector, handle);
                processStatusVector();
                switchState(TransactionState.COMMITTED);
            }
        } catch (SQLException e) {
            exceptionListenerDispatcher.errorOccurred(e);
            throw e;
        } finally {
            final TransactionState transactionState = getState();
            if (transactionState != TransactionState.COMMITTED) {
                String message = "Commit not completed, state was " + transactionState;
                log.warn(message + "; see debug level for stacktrace");
                if (log.isDebugEnabled()) {
                    log.debug(message, new RuntimeException("Commit not completed"));
                }
            }
        }
    }

    @Override
    public void rollback() throws SQLException {
        try {
            synchronized (getSynchronizationObject()) {
                final JnaDatabase db = getDatabase();
                db.checkConnected();
                switchState(TransactionState.ROLLING_BACK);
                clientLibrary.isc_rollback_transaction(statusVector, handle);
                processStatusVector();
                switchState(TransactionState.ROLLED_BACK);
            }
        } catch (SQLException e) {
            exceptionListenerDispatcher.errorOccurred(e);
            throw e;
        } finally {
            final TransactionState transactionState = getState();
            if (transactionState != TransactionState.ROLLED_BACK) {
                String message = "Rollback not completed, state was " + transactionState;
                log.warn(message + "; see debug level for stacktrace");
                if (log.isDebugEnabled()) {
                    log.debug(message, new RuntimeException("Rollback not completed"));
                }
            }
        }
    }

    @Override
    public void prepare(byte[] recoveryInformation) throws SQLException {
        boolean noRecoveryInfo = recoveryInformation == null || recoveryInformation.length == 0;
        try {
            synchronized (getSynchronizationObject()) {
                final JnaDatabase db = getDatabase();
                db.checkConnected();
                switchState(TransactionState.PREPARING);
                if (noRecoveryInfo) {
                    clientLibrary.isc_prepare_transaction(statusVector, handle);
                } else {
                    clientLibrary.isc_prepare_transaction2(statusVector, handle, (short) recoveryInformation.length,
                            recoveryInformation);
                }
                processStatusVector();
                switchState(TransactionState.PREPARED);
            }
        } catch (SQLException e) {
            exceptionListenerDispatcher.errorOccurred(e);
            throw e;
        } finally {
            if (getState() != TransactionState.PREPARED) {
                String message = "Prepare not completed";
                log.warn(message + "; see debug level for stacktrace");
                if (log.isDebugEnabled()) {
                    log.debug(message, new RuntimeException(message));
                }
            }
        }
    }

    @Override
    public byte[] getTransactionInfo(byte[] requestItems, int maxBufferLength) throws SQLException {
        try {
            final ByteBuffer responseBuffer = ByteBuffer.allocateDirect(maxBufferLength);
            synchronized (getSynchronizationObject()) {
                final JnaDatabase db = getDatabase();
                db.checkConnected();
                clientLibrary.isc_transaction_info(statusVector, handle, (short) requestItems.length, requestItems,
                        (short) maxBufferLength, responseBuffer);
                processStatusVector();
            }
            final byte[] responseArray = new byte[maxBufferLength];
            responseBuffer.get(responseArray);
            return responseArray;
        } catch (SQLException e) {
            exceptionListenerDispatcher.errorOccurred(e);
            throw e;
        }
    }

    private void processStatusVector() throws SQLException {
        getDatabase().processStatusVector(statusVector, null);
    }
}
