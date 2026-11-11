package io.github.qianlixunbai.workspace.finance;

import io.github.qianlixunbai.workspace.common.ErrorCode;
import io.github.qianlixunbai.workspace.common.WorkspaceException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinanceTransactionSearchTest {
    @Test void literalSearchBindsBothLedgerOwnersAndTheSameFilterBeforeCountAndPagination() throws Exception {
        var identity=mock(FinanceAccountQueries.class);var data=mock(DataSource.class);var connection=mock(Connection.class);
        var inside=new AtomicBoolean();var sqls=new ArrayList<String>();var parameters=new ArrayList<Map<Integer,Object>>();
        when(data.getConnection()).thenReturn(connection);
        when(identity.query(any())).thenAnswer(call->{
            Function<Long,?> read=call.getArgument(0);inside.set(true);
            try{return read.apply(42L);}finally{inside.set(false);}
        });
        when(connection.prepareStatement(anyString())).thenAnswer(call->{
            assertTrue(inside.get());String sql=call.getArgument(0);sqls.add(sql);
            var params=new LinkedHashMap<Integer,Object>();parameters.add(params);
            var statement=mock(PreparedStatement.class);var rows=mock(ResultSet.class);
            doAnswer(binding->{params.put(binding.getArgument(0),binding.getArgument(1));return null;}).when(statement).setObject(anyInt(),any());
            doAnswer(binding->{params.put(binding.getArgument(0),binding.getArgument(1));return null;}).when(statement).setString(anyInt(),anyString());
            when(statement.executeQuery()).thenReturn(rows);
            if(sql.startsWith("SELECT count(*)")){
                var metadata=mock(ResultSetMetaData.class);when(metadata.getColumnCount()).thenReturn(1);when(rows.getMetaData()).thenReturn(metadata);
                when(rows.next()).thenReturn(true,false);when(rows.getLong(1)).thenReturn(21L);
            }else when(rows.next()).thenReturn(false);
            return statement;
        });
        String query="订单😀 %_&'#--";
        var result=new FinanceTransactionQueries(identity,data).list(2,10,"17","2026-10-01","2026-10-31","TRANSFER","","",query);
        assertEquals(query,result.query());assertEquals("21",result.total());assertEquals(2,result.page());assertTrue(result.items().isEmpty());
        assertEquals(2,sqls.size());
        for(String sql:sqls){
            assertTrue(sql.contains("t.user_id=?"));assertTrue(sql.contains("a.user_id=t.user_id"));assertTrue(sql.contains("b.user_id=t.user_id"));
            assertTrue(sql.contains("(account_id=? OR to_account_id=?)"));assertTrue(sql.contains("position(? in coalesce(description,''))>0"));assertFalse(sql.contains(query));
        }
        assertEquals(sqls.get(0).substring("SELECT count(*)".length()),sqls.get(1).substring("SELECT *".length(),sqls.get(1).indexOf(" ORDER BY")));
        assertEquals(List.of(42L,42L,17L,17L,java.time.LocalDateTime.parse("2026-10-01T00:00"),java.time.LocalDateTime.parse("2026-11-01T00:00"),"TRANSFER",query),new ArrayList<>(parameters.get(0).values()));
        assertEquals(10,parameters.get(1).get(9));assertEquals(20L,parameters.get(1).get(10));
        verify(identity,times(1)).query(any());
    }

    @Test void invalidSearchAndChangedIdentityCannotReachLedgerAndControllerRejectsDuplicateOrInjectedParameters() {
        var identity=mock(FinanceAccountQueries.class);var data=mock(DataSource.class);var queries=new FinanceTransactionQueries(identity,data);
        for(String invalid:new String[]{" ","x\n", "x\u0085", "\ud800", "字".repeat(129),null})
            assertEquals(ErrorCode.INVALID_REQUEST,assertThrows(WorkspaceException.class,()->queries.list(0,10,"","","","ALL","","",invalid)).error().code());
        verifyNoInteractions(identity,data);
        var controller=new FinanceTransactionController(queries);
        for(String name:List.of("query","userId")){
            var request=new MockHttpServletRequest();request.addParameter(name,name.equals("query")?new String[]{"first","second"}:new String[]{"77"});
            assertEquals(ErrorCode.INVALID_REQUEST,assertThrows(WorkspaceException.class,()->controller.list(0,10,"","","","ALL","","","literal",request)).error().code());
        }
        verifyNoInteractions(identity,data);
        when(identity.query(any())).thenThrow(new WorkspaceException(ErrorCode.FINANCE_BINDING_INVALID,"FINANCE"));
        assertEquals(ErrorCode.FINANCE_BINDING_INVALID,assertThrows(WorkspaceException.class,()->queries.list(0,10,"","","","ALL","","","literal")).error().code());
        verifyNoInteractions(data);
    }
}
