/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.server.rest.transactional;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import org.neo4j.cypher.SyntaxException;
import org.neo4j.graphdb.Result;
import org.neo4j.kernel.api.exceptions.Status;
import org.neo4j.kernel.impl.query.QueryExecutionEngine;
import org.neo4j.kernel.impl.query.QueryExecutionKernelException;
import org.neo4j.kernel.impl.util.StringLogger;
import org.neo4j.server.rest.transactional.error.Neo4jError;
import org.neo4j.server.rest.web.TransactionUriScheme;

import static java.util.Arrays.asList;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import static org.neo4j.helpers.collection.MapUtil.map;
import static org.neo4j.server.rest.transactional.StubStatementDeserializer.statements;

public class TransactionHandleTest
{
    @Test
    public void shouldExecuteStatements() throws Exception
    {
        // given
        TransitionalPeriodTransactionMessContainer kernel = mockKernel();

        QueryExecutionEngine executionEngine = mock( QueryExecutionEngine.class );
        Result executionResult = mock( Result.class );
        when( executionEngine.executeQuery( "query", map() ) ).thenReturn( executionResult );
        TransactionRegistry registry = mock( TransactionRegistry.class );
        when( registry.begin( any( TransactionHandle.class ) ) ).thenReturn( 1337l );
        TransactionHandle handle = new TransactionHandle( kernel, executionEngine,
                registry, uriScheme, StringLogger.DEV_NULL );
        ExecutionResultSerializer output = mock( ExecutionResultSerializer.class );

        // when
        handle.execute( statements( new Statement( "query", map(), false, (ResultDataContent[])null ) ), output );

        // then
        verify( executionEngine ).executeQuery( "query", map() );

        InOrder outputOrder = inOrder( output );
        outputOrder.verify( output ).transactionCommitUri( uriScheme.txCommitUri( 1337 ) );
        outputOrder.verify( output ).statementResult( executionResult, false, (ResultDataContent[])null );
        outputOrder.verify( output ).transactionStatus( anyLong() );
        outputOrder.verify( output ).errors( argThat( hasNoErrors() ) );
        outputOrder.verify( output ).finish();
        verifyNoMoreInteractions( output );
    }

    @Test
    public void shouldSuspendTransactionAndReleaseForOtherRequestsAfterExecutingStatements() throws Exception
    {
        // given
        TransitionalPeriodTransactionMessContainer kernel = mockKernel();
        TransitionalTxManagementKernelTransaction transactionContext = kernel.newTransaction();

        TransactionRegistry registry = mock( TransactionRegistry.class );

        QueryExecutionEngine executionEngine = mock( QueryExecutionEngine.class );
        Result executionResult = mock( Result.class );
        when( executionEngine.executeQuery( "query", map() ) ).thenReturn( executionResult );
        when( registry.begin( any( TransactionHandle.class ) ) ).thenReturn( 1337l );
        TransactionHandle handle = new TransactionHandle( kernel, executionEngine,
                registry, uriScheme, StringLogger.DEV_NULL );
        ExecutionResultSerializer output = mock( ExecutionResultSerializer.class );

        // when
        handle.execute( statements( new Statement( "query", map(), false, (ResultDataContent[]) null ) ), output );

        // then
        InOrder transactionOrder = inOrder( transactionContext, registry );
        transactionOrder.verify( transactionContext ).suspendSinceTransactionsAreStillThreadBound();
        transactionOrder.verify( registry ).release( 1337l, handle );

        InOrder outputOrder = inOrder( output );
        outputOrder.verify( output ).transactionCommitUri( uriScheme.txCommitUri( 1337 ) );
        outputOrder.verify( output ).statementResult( executionResult, false, (ResultDataContent[])null );
        outputOrder.verify( output ).transactionStatus( anyLong() );
        outputOrder.verify( output ).errors( argThat( hasNoErrors() ) );
        outputOrder.verify( output ).finish();
        verifyNoMoreInteractions( output );
    }

    @Test
    public void shouldResumeTransactionWhenExecutingStatementsOnSecondRequest() throws Exception
    {
        // given
        TransitionalPeriodTransactionMessContainer kernel = mockKernel();
        TransitionalTxManagementKernelTransaction transactionContext = kernel.newTransaction();

        TransactionRegistry registry = mock( TransactionRegistry.class );
        QueryExecutionEngine executionEngine = mock( QueryExecutionEngine.class );
        when( registry.begin( any( TransactionHandle.class ) ) ).thenReturn( 1337l );
        TransactionHandle handle = new TransactionHandle( kernel, executionEngine, registry, uriScheme,
                StringLogger.DEV_NULL );

        ExecutionResultSerializer output = mock( ExecutionResultSerializer.class );

        handle.execute( statements( new Statement( "query", map(), false, (ResultDataContent[])null ) ), output );
        reset( transactionContext, registry, executionEngine, output );
        Result executionResult = mock( Result.class );
        when( executionEngine.executeQuery( "query", map() ) ).thenReturn( executionResult );

        // when
        handle.execute( statements( new Statement( "query", map(), false, (ResultDataContent[])null ) ), output );

        // then
        InOrder order = inOrder( transactionContext, registry, executionEngine );
        order.verify( transactionContext ).resumeSinceTransactionsAreStillThreadBound();
        order.verify( executionEngine ).executeQuery( "query", map() );
        order.verify( transactionContext ).suspendSinceTransactionsAreStillThreadBound();
        order.verify( registry ).release( 1337l, handle );

        InOrder outputOrder = inOrder( output );
        outputOrder.verify( output ).transactionCommitUri( uriScheme.txCommitUri( 1337 ) );
        outputOrder.verify( output ).statementResult( executionResult, false, (ResultDataContent[])null );
        outputOrder.verify( output ).transactionStatus( anyLong() );
        outputOrder.verify( output ).errors( argThat( hasNoErrors() ) );
        outputOrder.verify( output ).finish();
        verifyNoMoreInteractions( output );
    }

    @Test
    public void shouldCommitSinglePeriodicCommitStatement() throws Exception
    {
        // given
        String queryText = "USING PERIODIC COMMIT CREATE()";
        TransitionalPeriodTransactionMessContainer kernel = mockKernel();

        QueryExecutionEngine executionEngine = mock( QueryExecutionEngine.class );
        Result executionResult = mock( Result.class );
        when( executionEngine.isPeriodicCommit( queryText) ).thenReturn( true );
        when( executionEngine.executeQuery( eq( queryText ), eq( map() ) ) )
                .thenReturn( executionResult );

        TransactionRegistry registry = mock( TransactionRegistry.class );
        when( registry.begin( any( TransactionHandle.class ) ) ).thenReturn( 1337l );
        TransactionHandle handle = new TransactionHandle( kernel, executionEngine, registry, uriScheme, StringLogger.DEV_NULL );
        ExecutionResultSerializer output = mock( ExecutionResultSerializer.class );
        Statement statement = new Statement( queryText, map(), false, (ResultDataContent[]) null );

        // when
        handle.commit(statements(statement), output, true);

        // then
        verify( executionEngine ).isPeriodicCommit( queryText );
        verify( executionEngine ).executeQuery(queryText, map());

        InOrder outputOrder = inOrder( output );
        outputOrder.verify( output ).statementResult( executionResult, false, (ResultDataContent[]) null );
        outputOrder.verify( output ).errors( argThat( hasNoErrors() ) );
        outputOrder.verify( output ).finish();
        verifyNoMoreInteractions( output );
    }

    @Test
    public void shouldCommitTransactionAndTellRegistryToForgetItsHandle() throws Exception
    {
        // given
        TransitionalPeriodTransactionMessContainer kernel = mockKernel();
        TransitionalTxManagementKernelTransaction transactionContext = kernel.newTransaction();

        TransactionRegistry registry = mock( TransactionRegistry.class );

        QueryExecutionEngine engine = mock( QueryExecutionEngine.class );
        Result result = mock( Result.class );
        when( engine.executeQuery( "query", map() ) ).thenReturn( result );
        when( registry.begin( any( TransactionHandle.class ) ) ).thenReturn( 1337l );
        TransactionHandle handle = new TransactionHandle( kernel, engine,
                                                          registry, uriScheme, StringLogger.DEV_NULL );
        ExecutionResultSerializer output = mock( ExecutionResultSerializer.class );

        // when
        Statement statement = new Statement("query", map(), false, (ResultDataContent[]) null);
        handle.commit( statements( statement ), output, false );

        // then
        InOrder transactionOrder = inOrder( transactionContext, registry );
        transactionOrder.verify( transactionContext ).commit();
        transactionOrder.verify( registry ).forget( 1337l );

        InOrder outputOrder = inOrder( output );
        outputOrder.verify( output ).statementResult( result, false, (ResultDataContent[])null );
        outputOrder.verify( output ).errors( argThat( hasNoErrors() ) );
        outputOrder.verify( output ).finish();
        verifyNoMoreInteractions( output );
    }

    @Test
    public void shouldRollbackTransactionAndTellRegistryToForgetItsHandle() throws Exception
    {
        // given
        TransitionalPeriodTransactionMessContainer kernel = mockKernel();
        TransitionalTxManagementKernelTransaction transactionContext = kernel.newTransaction();

        TransactionRegistry registry = mock( TransactionRegistry.class );
        when( registry.begin( any( TransactionHandle.class ) ) ).thenReturn( 1337l );
        TransactionHandle handle = new TransactionHandle( kernel, mock( QueryExecutionEngine.class ),
                registry, uriScheme, StringLogger.DEV_NULL );

        ExecutionResultSerializer output = mock( ExecutionResultSerializer.class );

        // when
        handle.rollback( output );

        // then
        InOrder transactionOrder = inOrder( transactionContext, registry );
        transactionOrder.verify( transactionContext ).rollback();
        transactionOrder.verify( registry ).forget( 1337l );

        InOrder outputOrder = inOrder( output );
        outputOrder.verify( output ).errors( argThat( hasNoErrors() ) );
        outputOrder.verify( output ).finish();
        verifyNoMoreInteractions( output );
    }

    @Test
    public void shouldCreateTransactionContextOnlyWhenFirstNeeded() throws Exception
    {
        // given
        TransitionalPeriodTransactionMessContainer kernel = mockKernel();
        ExecutionResultSerializer output = mock( ExecutionResultSerializer.class );
        TransactionRegistry registry = mock( TransactionRegistry.class );

        // when
        QueryExecutionEngine engine = mock( QueryExecutionEngine.class );
        Result executionResult = mock( Result.class );
        when( engine.executeQuery( "query", map() ) ).thenReturn( executionResult );
        when( registry.begin( any( TransactionHandle.class ) ) ).thenReturn( 1337l );
        TransactionHandle handle = new TransactionHandle( kernel, engine,
                registry, uriScheme, StringLogger.DEV_NULL );

        // then
        verifyZeroInteractions( kernel );

        // when
        handle.execute( statements( new Statement( "query", map(), false, (ResultDataContent[])null ) ), output );

        // then
        verify( kernel ).newTransaction();

        InOrder outputOrder = inOrder( output );
        outputOrder.verify( output ).transactionCommitUri( uriScheme.txCommitUri( 1337 ) );
        outputOrder.verify( output ).statementResult( executionResult, false, (ResultDataContent[])null );
        outputOrder.verify( output ).transactionStatus( anyLong() );
        outputOrder.verify( output ).errors( argThat( hasNoErrors() ) );
        outputOrder.verify( output ).finish();
        verifyNoMoreInteractions( output );
    }

    @Test
    public void shouldRollbackTransactionIfExecutionErrorOccurs() throws Exception
    {
        // given
        TransitionalPeriodTransactionMessContainer kernel = mockKernel();
        TransitionalTxManagementKernelTransaction transactionContext = kernel.newTransaction();

        TransactionRegistry registry = mock( TransactionRegistry.class );

        QueryExecutionEngine executionEngine = mock( QueryExecutionEngine.class );
        when( executionEngine.executeQuery( "query", map() ) ).thenThrow( new NullPointerException() );

        when( registry.begin( any( TransactionHandle.class ) ) ).thenReturn( 1337l );
        TransactionHandle handle = new TransactionHandle( kernel, executionEngine, registry, uriScheme,
                StringLogger.DEV_NULL );
        ExecutionResultSerializer output = mock( ExecutionResultSerializer.class );

        // when
        handle.execute( statements( new Statement( "query", map(), false, (ResultDataContent[])null ) ), output );

        // then
        verify( transactionContext ).rollback();
        verify( registry ).forget( 1337l );

        InOrder outputOrder = inOrder( output );
        outputOrder.verify( output ).transactionCommitUri( uriScheme.txCommitUri( 1337 ) );
        outputOrder.verify( output ).errors( argThat( hasErrors( Status.Statement.ExecutionFailure ) ) );
        outputOrder.verify( output ).finish();
        verifyNoMoreInteractions( output );
    }

    @Test
    public void shouldLogMessageIfCommitErrorOccurs() throws Exception
    {
        // given
        TransitionalPeriodTransactionMessContainer kernel = mockKernel();
        TransitionalTxManagementKernelTransaction transactionContext = kernel.newTransaction();
        doThrow( new NullPointerException() ).when( transactionContext ).commit();

        StringLogger log = mock( StringLogger.class );

        TransactionRegistry registry = mock( TransactionRegistry.class );

        QueryExecutionEngine engine = mock( QueryExecutionEngine.class );
        Result executionResult = mock( Result.class );
        when( engine.executeQuery( "query", map() ) ).thenReturn( executionResult );
        when( registry.begin( any( TransactionHandle.class ) ) ).thenReturn( 1337l );
        TransactionHandle handle = new TransactionHandle( kernel, engine, registry, uriScheme, log );
        ExecutionResultSerializer output = mock( ExecutionResultSerializer.class );

        // when
        Statement statement = new Statement( "query", map(), false, (ResultDataContent[]) null );
        handle.commit( statements( statement ), output, false );

        // then
        verify( log ).error( eq( "Failed to commit transaction." ), any( NullPointerException.class ) );
        verify( registry ).forget( 1337l );

        InOrder outputOrder = inOrder( output );
        outputOrder.verify( output ).statementResult( executionResult, false, (ResultDataContent[])null );
        outputOrder.verify( output ).errors( argThat( hasErrors( Status.Transaction.CouldNotCommit ) ) );
        outputOrder.verify( output ).finish();
        verifyNoMoreInteractions( output );
    }

    @Test
    public void shouldLogMessageIfCypherSyntaxErrorOccurs() throws Exception
    {
        // given
        TransitionalPeriodTransactionMessContainer kernel = mockKernel();

        QueryExecutionEngine executionEngine = mock( QueryExecutionEngine.class );
        when( executionEngine.executeQuery( "matsch (n) return n", map() ) )
                .thenThrow( new QueryExecutionKernelException( new SyntaxException( "did you mean MATCH?" ) ) );

        StringLogger log = mock( StringLogger.class );

        TransactionRegistry registry = mock( TransactionRegistry.class );
        when( registry.begin( any( TransactionHandle.class ) ) ).thenReturn( 1337l );
        TransactionHandle handle = new TransactionHandle( kernel, executionEngine, registry, uriScheme, log );
        ExecutionResultSerializer output = mock( ExecutionResultSerializer.class );

        // when
        Statement statement = new Statement("matsch (n) return n", map(), false, (ResultDataContent[]) null);
        handle.commit( statements( statement ), output, false );

        // then
        verify( registry ).forget( 1337l );

        InOrder outputOrder = inOrder( output );
        outputOrder.verify( output ).errors( argThat( hasErrors( Status.Statement.InvalidSyntax ) ) );
        outputOrder.verify( output ).finish();
        verifyNoMoreInteractions( output );
    }

    @Test
    public void shouldHandleExecutionEngineThrowingUndeclaredCheckedExceptions() throws Exception
    {
        // given

        QueryExecutionEngine executionEngine = mock( QueryExecutionEngine.class );
        when( executionEngine.executeQuery( "match (n) return n", map() ) ).thenAnswer( new Answer()
        {
            @Override
            public Object answer( InvocationOnMock invocationOnMock ) throws Throwable { throw new Exception("BOO"); }
        });

        TransactionRegistry registry = mock( TransactionRegistry.class );
        when( registry.begin( any( TransactionHandle.class ) ) ).thenReturn( 1337l );
        TransactionHandle handle = new TransactionHandle( mockKernel(), executionEngine, registry, uriScheme,
                mock( StringLogger.class ) );
        ExecutionResultSerializer output = mock( ExecutionResultSerializer.class );

        // when
        Statement statement = new Statement( "match (n) return n", map(), false, (ResultDataContent[]) null );
        handle.commit( statements( statement ), output, false );

        // then
        verify( registry ).forget( 1337l );

        InOrder outputOrder = inOrder( output );
        outputOrder.verify( output ).errors( argThat( hasErrors( Status.Statement.ExecutionFailure ) ) );
        outputOrder.verify( output ).finish();
        verifyNoMoreInteractions( output );
    }

    @Test
    public void shouldInterruptTransaction() throws Exception
    {
        // given
        TransitionalPeriodTransactionMessContainer kernel = mockKernel();
        TransitionalTxManagementKernelTransaction tx = mock( TransitionalTxManagementKernelTransaction.class );
        when( kernel.newTransaction() ).thenReturn( tx );
        TransactionRegistry registry = mock( TransactionRegistry.class );
        when( registry.begin( any( TransactionHandle.class ) ) ).thenReturn( 1337l );
        QueryExecutionEngine executionEngine = mock( QueryExecutionEngine.class );
        TransactionHandle handle = new TransactionHandle( kernel, executionEngine, registry, uriScheme, StringLogger.DEV_NULL );

        ExecutionResultSerializer output = mock( ExecutionResultSerializer.class );
        handle.execute( statements(), output );

        // when
        handle.terminate();

        // then
        verify( tx, times( 1 ) ).terminate();
    }

    private static final TransactionUriScheme uriScheme = new TransactionUriScheme()
    {
        @Override
        public URI txUri( long id )
        {
            return URI.create( "transaction/" + id );
        }

        @Override
        public URI txCommitUri( long id )
        {
            return URI.create( "transaction/" + id + "/commit" );
        }
    };

    private TransitionalPeriodTransactionMessContainer mockKernel()
    {
        TransitionalTxManagementKernelTransaction context = mock( TransitionalTxManagementKernelTransaction.class );
        TransitionalPeriodTransactionMessContainer kernel = mock( TransitionalPeriodTransactionMessContainer.class );
        when( kernel.newTransaction() ).thenReturn( context );
        return kernel;
    }

    private static Matcher<Iterable<Neo4jError>> hasNoErrors()
    {
        return hasErrors();
    }

    private static Matcher<Iterable<Neo4jError>> hasErrors( Status... codes )
    {
        final Set<Status> expectedErrorsCodes = new HashSet<>( asList( codes ) );

        return new TypeSafeMatcher<Iterable<Neo4jError>>()
        {
            @Override
            protected boolean matchesSafely( Iterable<Neo4jError> item )
            {
                Set<Status> actualErrorCodes = new HashSet<>();
                for ( Neo4jError neo4jError : item )
                {
                    actualErrorCodes.add( neo4jError.status() );
                }
                return expectedErrorsCodes.equals( actualErrorCodes );
            }

            @Override
            public void describeTo( Description description )
            {
                description.appendText( "Errors with set of codes" ).appendValue( expectedErrorsCodes );
            }
        };
    }
}
