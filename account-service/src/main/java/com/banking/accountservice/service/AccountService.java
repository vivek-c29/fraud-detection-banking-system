package com.banking.accountservice.service;

import com.banking.accountservice.dto.AccountResponse;
import com.banking.accountservice.dto.CreateAccountRequest;
import com.banking.accountservice.entity.Account;
import com.banking.accountservice.entity.AccountStatus;
import com.banking.accountservice.entity.AccountType;
import com.banking.accountservice.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
    private static SecureRandom secureRandom = new SecureRandom();

    public String generateAccountNumber(){
        String accountNumber;
        do {
            long number = secureRandom.nextLong(1_000_000_000_000L);
            accountNumber = String.format("%012d", number);
        } while (accountRepository.existsByAccountNumber(accountNumber));
        return accountNumber;
    }

    private AccountResponse mapToResponse(Account account){
        AccountResponse accountResponse = new AccountResponse();
        accountResponse.setId(account.getId());
        accountResponse.setAccountNumber(account.getAccountNumber());
        accountResponse.setAccountHolderName(account.getAccountHolderName());
        accountResponse.setPhone(account.getPhone());
        accountResponse.setEmail(account.getEmail());
        accountResponse.setAccountType(account.getAccountType());
        accountResponse.setStatus(account.getStatus());
        accountResponse.setBalance(account.getBalance());
        accountResponse.setDailyTransactionLimit(account.getDailyTransactionLimit());
        accountResponse.setCreatedAt(account.getCreatedAt());
        return accountResponse;
    }

    public AccountResponse createAccount(CreateAccountRequest request) {
        log.info("Creating account for: {}", request.getEmail());

        if(accountRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("Account with email " + request.getEmail() + " already exists");
        }

        Account account = new Account();
        account.setAccountHolderName(request.getAccountHolderName());
        account.setPhone(request.getPhone());
        account.setEmail(request.getEmail());
        account.setAccountType(request.getAccountType());
        account.setBalance(request.getInitialDeposit());
        account.setStatus(AccountStatus.ACTIVE);
        account.setBalance(request.getInitialDeposit());
        account.setAccountNumber(generateAccountNumber());
        account.setDailyTransactionLimit(
                request.getAccountType() == AccountType.SAVINGS ?
                        new BigDecimal("100000") :
                        new BigDecimal("500000")
        );

        Account savedAccount = accountRepository.save(account);
        log.info("Account created successfully: {}", savedAccount.getAccountNumber());
        return mapToResponse(savedAccount);
    }

    public AccountResponse getAccount(String accountNumber) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new RuntimeException("Account not found"));
        return mapToResponse(account);
    }

    public BigDecimal getBalance(String accountNumber) {

        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new RuntimeException("Account not found"));
        return account.getBalance();
    }

    /**
     * Block account - called by fraud detection service via kafka
     * @param accountNumber
     */

    public void blockAccount(String accountNumber) {
        log.info("Blocking account for: {}", accountNumber);
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new RuntimeException("Account not found"));
        account.setStatus(AccountStatus.BLOCKED);
        accountRepository.save(account);
        log.info("Account blocked successfully: {}", accountNumber);
    }

    /**
     * Deduct balance - from sender account
     * - called by transaction service via feign
     * @param accountNumber
     * @param amount
     */
    @Transactional
    public void deductBalance(String accountNumber, BigDecimal amount) {
        log.info("Deducting balance {} from account: {}", amount, accountNumber);
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new RuntimeException("Account not found: " + accountNumber));

        if(account.getStatus() != AccountStatus.ACTIVE) {
            throw new RuntimeException("Account is not active: " + accountNumber);
        }

        if(account.getBalance().compareTo(amount) < 0) {
            throw new RuntimeException("Insufficient balance for account: " + account.getAccountNumber());
        }

        account.setBalance(account.getBalance().subtract(amount));
        accountRepository.save(account);
        log.info("Balance updated. New Balance: {}", account.getBalance());
    }

    /**
     * Credit balance - to receiver account or deposit from payment
     * - called by transaction service or kafka consumer
     * @param accountNumber
     * @param amount
     */
    @Transactional
    public void creditBalance(String accountNumber, BigDecimal amount) {
        log.info("Crediting balance {} to account: {}", amount, accountNumber);
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new RuntimeException("Account not found: " + accountNumber));

        if(account.getStatus() != AccountStatus.ACTIVE) {
            throw new RuntimeException("Account is not active: " + accountNumber);
        }

        account.setBalance(account.getBalance().add(amount));
        accountRepository.save(account);
        log.info("Balance credited. New Balance: {}", account.getBalance());
    }
}
