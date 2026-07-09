package com.instalego.controller;

import com.instalego.dto.BankRequest;
import com.instalego.model.Bank;
import com.instalego.service.BankService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/banks")
@RequiredArgsConstructor
public class BankController {

    private final BankService bankService;

    @PostMapping
    public ResponseEntity<?> createBank(@Valid @RequestBody BankRequest request) {
        try {
            Bank bank = bankService.createBank(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(bank);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<List<Bank>> getAllBanks() {
        return ResponseEntity.ok(bankService.getAllBanks());
    }

    @GetMapping("/with-template")
    public ResponseEntity<List<Bank>> getBanksWithTemplate() {
        return ResponseEntity.ok(bankService.getBanksWithActiveTemplate());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getBank(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(bankService.getBankById(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
