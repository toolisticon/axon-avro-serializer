package bankaccount.projection;

import bankaccount.event.BankAccountCreated;
import bankaccount.event.MoneyDeposited;
import bankaccount.event.MoneyWithdrawn;
import org.axonframework.eventhandling.EventHandler;
import org.axonframework.queryhandling.QueryHandler;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Objects.requireNonNull;

public class CurrentBalanceProjection {

  private final Map<String, Integer> accounts;

  public CurrentBalanceProjection() {
    this(new ConcurrentHashMap<>());
  }

  public CurrentBalanceProjection(final Map<String, Integer> accounts) {
    this.accounts = accounts;
  }

  @EventHandler
  public void on(BankAccountCreated evt) {
    accounts.put(evt.getAccountId(), evt.getInitialBalance());
  }

  @EventHandler
  public void on(MoneyWithdrawn evt) {
    newBalance(evt.getAccountId(), -evt.getAmount());
  }

  @EventHandler
  public void on(MoneyDeposited evt) {
    newBalance(evt.getAccountId(), evt.getAmount());
  }

  @QueryHandler
  public Optional<CurrentBalance> getCurrentBalance(Queries.CurrentBalanceQuery query) {
    return Optional.of(accounts.get(query.accountId)).map(it -> new CurrentBalance(query.accountId, it));
  }

  private void newBalance(String accountId, int amount) {
    accounts.compute(accountId, (k, v) -> requireNonNull(v) + amount);
  }

  public enum Queries {
    ;

    public static class CurrentBalanceQuery {
      private final String accountId;

      public CurrentBalanceQuery(String accountId) {
        this.accountId = accountId;
      }

      public String getAccountId() {
        return accountId;
      }
    }
  }

  public static class CurrentBalance {
    private final String accountId;
    private final int balance;

    public CurrentBalance(String accountId, int balance) {
      this.accountId = accountId;
      this.balance = balance;
    }

    public String getAccountId() {
      return accountId;
    }

    public int getBalance() {
      return balance;
    }

    @Override
    public String toString() {
      return "CurrentBalance{" +
        "accountId='" + accountId + '\'' +
        ", balance=" + balance +
        '}';
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      CurrentBalance that = (CurrentBalance) o;
      return balance == that.balance &&
        accountId.equals(that.accountId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(accountId, balance);
    }
  }
}
