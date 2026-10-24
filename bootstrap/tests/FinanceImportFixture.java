import java.sql.*;
import java.util.*;
import java.time.Instant;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import com.financeos.module.importing.dto.TransactionImportReceipt.AccountImpact;
import com.financeos.module.importing.dto.TransactionImportReceipt.TransactionReference;

/** Controlled NEW-cluster evidence, not an import-confirm transaction E2E. Never used against existing fixtures. */
final class FinanceImportFixture {
    static void prepare(Connection db, String reader) throws Exception {
        try (var s = db.createStatement()) {
            s.execute("INSERT INTO categories(id,name,type,is_system) VALUES(200,'Synthetic import expense','EXPENSE',true)");
            for (int i = 1; i <= 25; i++) s.execute("INSERT INTO accounts(id,user_id,name,type,currency,balance,status) VALUES(" + (1000+i) + ",1,'Import synthetic " + i + "','CASH','CNY',0.00,'ACTIVE')");
        }
        batch(db, 101, 1, "D:\\private\\bank-statement.csv", 2, 2, false, false, false);
        batch(db, 102, 1, "many-rows.csv", 25, 25, false, false, false);
        batch(db, 103, 1, "missing-evidence.csv", 1, 0, false, false, false);
        batch(db, 104, 1, "bad-digest.csv", 1, 1, true, false, false);
        batch(db, 105, 1, "bad-row-count.csv", 2, 2, false, true, false);
        batch(db, 106, 1, "missing-impact.csv", 1, 1, false, false, true);
        batch(db, 201, 2, "bob-import.csv", 1, 1, false, false, false);
        // READY/EXPIRED sessions without committed batches must never become history rows.
        try (var s = db.createStatement()) {
            for (String state : List.of("PREVIEW_READY", "EXPIRED")) {
                String n = state.equals("EXPIRED") ? "998" : "999";
                s.execute("INSERT INTO transaction_import_sessions(id,user_id,preallocated_batch_id,status,original_file_name,file_size,file_digest,temporary_storage_reference,options_digest,expires_at) VALUES('" + id("2", Integer.parseInt(n)) + "',1,'" + id("1", Integer.parseInt(n)) + "','" + state + "','preview-only.csv',1,repeat('a',64),'synthetic-only',repeat('b',64),now()+interval '1 day')");
            }
            // Source V20 permits later deletion while retaining immutable historical IDs.
            s.execute("DELETE FROM transactions WHERE id=10101 AND user_id=1");
            s.execute("GRANT SELECT ON public.transaction_import_batches,public.transaction_import_items,public.transaction_import_batch_account_impacts TO " + reader);
        }
    }
    static String id(String group, int n) { return "00000000-0000-0000-000" + group + "-" + String.format("%012d", n); }
    private static void batch(Connection db, int n, int user, String name, int total, int actual, boolean badDigest, boolean badCount, boolean missingImpact) throws Exception {
        UUID batch=UUID.fromString(id("1",n)), session=UUID.fromString(id("2",n));
        // Equal timestamps exercise UUID DESC ties; invalid evidence is older than the two valid Alice batches.
        Instant time=Instant.parse(n < 103 || n == 201 ? "2026-10-23T12:00:00.123456Z" : "2026-10-22T12:00:00.123456Z");
        var refs=new ArrayList<TransactionReference>(); var impacts=new ArrayList<AccountImpact>();
        for (int i=1;i<=actual;i++) {
            long account = user == 2 ? 3 : n == 102 ? 1000+i : i == 2 ? 4 : 1;
            long transaction=n*100L+i;
            String amount=n == 101 ? i == 1 ? "0.20" : "1.25" : "0.01";
            String type=n == 101 && i == 2 ? "EXPENSE" : "INCOME";
            try(var s=db.prepareStatement("INSERT INTO transactions(id,user_id,account_id,category_id,type,amount,currency,description,transacted_at) VALUES(?,?,?,?,?,?,?,'Synthetic historical import',?)")) {
                s.setLong(1,transaction);s.setInt(2,user);s.setLong(3,account);s.setInt(4,type.equals("EXPENSE")?200:1);s.setString(5,type);s.setBigDecimal(6,new BigDecimal(amount));s.setString(7,account==4?"USD":"CNY");s.setTimestamp(8,Timestamp.from(time));s.executeUpdate();
            }
            refs.add(new TransactionReference(i*2,transaction));
            BigDecimal before=new BigDecimal(n == 101 && i == 1 ? "9007199254740993.10" : "10.00");
            BigDecimal delta=new BigDecimal(amount).multiply(new BigDecimal(type.equals("EXPENSE")?"-1":"1"));
            impacts.add(new AccountImpact(account,1,before,delta,before.add(delta)));
        }
        // Aggregate repeated accounts exactly as the source confirmation service does.
        var grouped=new TreeMap<Long,AccountImpact>();
        for(var impact:impacts) grouped.merge(impact.accountId(),impact,(a,b)->new AccountImpact(a.accountId(),a.rowCount()+b.rowCount(),a.balanceBefore(),a.delta().add(b.delta()),a.balanceBefore().add(a.delta()).add(b.delta())));
        impacts=new ArrayList<>(grouped.values());
        if(badCount) impacts.set(0,new AccountImpact(impacts.getFirst().accountId(),total+1,impacts.getFirst().balanceBefore(),impacts.getFirst().delta(),impacts.getFirst().balanceAfter()));
        String input=session+"|"+batch+"|"+time+"|"+refs+"|"+impacts;
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        try(var s=db.prepareStatement("INSERT INTO transaction_import_sessions(id,user_id,preallocated_batch_id,status,original_file_name,file_size,file_digest,temporary_storage_reference,mapping_digest,options_digest,normalized_rows_digest,expires_at,consumed_at) VALUES(?,?,?,'CONSUMED',?,1,repeat('a',64),'synthetic-only',repeat('b',64),repeat('c',64),repeat('d',64),?,?)")) {
            s.setObject(1,session);s.setInt(2,user);s.setObject(3,batch);s.setString(4,name);s.setTimestamp(5,Timestamp.from(time.plusSeconds(3600)));s.setTimestamp(6,Timestamp.from(time));s.executeUpdate();
        }
        try(var s=db.prepareStatement("INSERT INTO transaction_import_batches(id,user_id,session_id,original_file_name,file_digest,mapping_digest,options_digest,normalized_rows_digest,contract_version,idempotency_key,request_hash,result_digest,status,total_rows,warning_count,confirmed_at) VALUES(?,?,?,?,repeat('a',64),?,repeat('c',64),repeat('d',64),'TRANSACTION_IMPORT_CONFIRM_V1',?,repeat('e',64),?,'CONFIRMED',?,1,?)")) {
            s.setObject(1,batch);s.setInt(2,user);s.setObject(3,session);s.setString(4,name);s.setString(5,String.format("%064x",n));s.setString(6,"synthetic-"+n);s.setString(7,badDigest?"0".repeat(64):digest);s.setInt(8,total);s.setTimestamp(9,Timestamp.from(time));s.executeUpdate();
        }
        for(var r:refs) try(var s=db.prepareStatement("INSERT INTO transaction_import_items(user_id,batch_id,source_row_number,canonical_row_fingerprint,warning_codes,created_transaction_id) VALUES(?,?,?,repeat('f',64),'SYNTHETIC',?)")) {
            s.setInt(1,user);s.setObject(2,batch);s.setInt(3,r.rowNumber());s.setLong(4,r.transactionId());s.executeUpdate();
        }
        if(!missingImpact) for(var a:impacts) try(var s=db.prepareStatement("INSERT INTO transaction_import_batch_account_impacts(user_id,batch_id,account_id,row_count,balance_before,delta,balance_after) VALUES(?,?,?,?,?,?,?)")) {
            s.setInt(1,user);s.setObject(2,batch);s.setLong(3,a.accountId());s.setInt(4,a.rowCount());s.setBigDecimal(5,a.balanceBefore());s.setBigDecimal(6,a.delta());s.setBigDecimal(7,a.balanceAfter());s.executeUpdate();
        }
    }
}
