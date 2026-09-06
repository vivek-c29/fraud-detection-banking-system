package com.banking.accountservice.dto;

import com.banking.accountservice.entity.AccountType;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateAccountRequest {

        @NotBlank(message = "Account holder name is required")
        private String accountHolderName;

        @NotBlank(message = "Phone number is required")
        private String phone;

        @NotBlank(message = "Email address is required")
        @Email(message = "Invalid email address")
        private String email;

        @Enumerated(EnumType.STRING)
        @NotNull(message = "Account type is required")
        private AccountType accountType;

        @NotNull(message = "Initial deposit is required")
        @Positive(message = "Initial deposit must be a positive value")
        private BigDecimal initialDeposit;

}
