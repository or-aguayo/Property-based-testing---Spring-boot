package com.app.transaction;

import com.app.account.Account;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountTransactionRepository extends JpaRepository<AccountTransaction, Long> {

    @Query("select t from AccountTransaction t "
            + "left join fetch t.sourceAccount "
            + "left join fetch t.targetAccount "
            + "where t.sourceAccount = :account or t.targetAccount = :account "
            + "order by t.occurredAt desc")
    List<AccountTransaction> findByAccount(@Param("account") Account account);
}
