package io.github.qianlixunbai.workspace.finance;

import com.financeos.module.account.entity.Account;
import com.financeos.module.account.service.AccountQueryService;
import io.github.qianlixunbai.workspace.common.ErrorCode;
import io.github.qianlixunbai.workspace.common.WorkspaceException;
import io.github.qianlixunbai.workspace.security.ClientIdentity;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Function;

/** The Native application boundary. No client/model-selected Finance user ID is accepted by queries. */
public final class FinanceAccountQueries {
    private final LocalFinanceProfile profile;
    private final FinanceIdentityRepository identities;
    private final AccountQueryService accounts;
    private final TransactionTemplate reads;

    FinanceAccountQueries(LocalFinanceProfile profile, FinanceIdentityRepository identities,
                          AccountQueryService accounts, PlatformTransactionManager financeTransactions) {
        this.profile = profile;
        this.identities = identities;
        this.accounts = accounts;
        reads = new TransactionTemplate(financeTransactions);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reads.setTimeout(30);
    }

    public record CnyBalance(String currency, String balance) {
        @Override public String toString() { return "FinanceBalance[private]"; }
    }

    public record AccountView(String id, String name, String type, String currency, String balance, String status) {
        @Override public String toString() { return "FinanceAccount[private]"; }
    }

    public record Overview(String state, AccountView account, CnyBalance cnyBalance) {
        @Override public String toString() { return "FinanceOverview[private]"; }
    }

    public Overview overview() {
        return query(userId -> {
            Long id = identities.firstCnyAccountId(userId);
            Account account = id == null ? null : accounts.findAccessibleAccount(userId, id);
            if (id != null && (account == null || !Long.valueOf(userId).equals(account.getUserId()) || !"CNY".equals(account.getCurrency())))
                throw failure(ErrorCode.FINANCE_BINDING_INVALID);
            return new Overview("READY", account == null ? null : new AccountView(account.getId().toString(), account.getName(),
                    account.getType(), account.getCurrency(), account.getBalance().toPlainString(), account.getStatus()),
                    new CnyBalance("CNY", accounts.sumBalanceByUser(userId).toPlainString()));
        });
    }

    FinanceIdentityRepository.Review reviewHistoricalBinding() {
        requireNative();
        try { profile.requireBinding(); throw failure(ErrorCode.FINANCE_BINDING_INVALID); }
        catch (WorkspaceException absent) { if (absent.error().code() != ErrorCode.FINANCE_NOT_BOUND) throw absent; }
        return reads.execute(status -> identities.review());
    }

    public CnyBalance cnyBalance() {
        return query(userId -> new CnyBalance("CNY", accounts.sumBalanceByUser(userId).toPlainString()));
    }

    public AccountView account(long accountId) {
        return query(userId -> {
            if (accountId < 1) throw failure(ErrorCode.INVALID_REQUEST);
            Account account = accounts.findAccessibleAccount(userId, accountId);
            if (account == null) throw failure(ErrorCode.FINANCE_ACCOUNT_NOT_FOUND);
            if (!Long.valueOf(userId).equals(account.getUserId())) throw failure(ErrorCode.FINANCE_BINDING_INVALID);
            return new AccountView(account.getId().toString(), account.getName(), account.getType(),
                    account.getCurrency(), account.getBalance().toPlainString(), account.getStatus());
        });
    }

    private <T> T query(Function<Long, T> operation) {
        requireNative();
        LocalFinanceProfile.Binding binding = profile.requireBinding(); // Deny before any database access.
        try {
            return reads.execute(status -> {
                var actual = identities.activeIdentity(binding.financeUserId());
                if (!binding.datasetFingerprint().equals(actual.datasetFingerprint())
                        || !binding.userFingerprint().equals(actual.userFingerprint())
                        || binding.financeUserId() != actual.userId()) throw failure(ErrorCode.FINANCE_BINDING_INVALID);
                return operation.apply(actual.userId());
            });
        } catch (DataAccessException | TransactionException unavailable) {
            throw failure(ErrorCode.FINANCE_UNAVAILABLE); // No SQL, database records or connection details in API errors.
        }
    }

    /** Native pipe only. Rechecks the exact server-owned review before CREATE_NEW; no HTTP/AI confirmation. */
    LocalFinanceProfile.Binding confirmHistoricalBinding(FinanceIdentityRepository.Identity reviewed) {
        requireNative();
        if (reviewed == null || reviewed.userId() < 1) throw failure(ErrorCode.FINANCE_BINDING_INVALID);
        try {
            return reads.execute(status -> {
                var actual = identities.activeIdentity(reviewed.userId());
                if (!reviewed.equals(actual)) throw failure(ErrorCode.FINANCE_BINDING_INVALID);
                return profile.createConfirmed(actual);
            });
        } catch (DataAccessException | TransactionException unavailable) {
            throw failure(ErrorCode.FINANCE_UNAVAILABLE);
        }
    }

    private static void requireNative() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getPrincipal() != ClientIdentity.NATIVE
                || authentication.getAuthorities().stream().noneMatch(a -> "ROLE_LOCAL_CLIENT".equals(a.getAuthority()))) {
            throw failure(ErrorCode.POLICY_DENIED);
        }
    }

    private static WorkspaceException failure(ErrorCode code) { return new WorkspaceException(code, "FINANCE"); }
}
