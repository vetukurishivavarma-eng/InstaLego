package com.instalego.service;

import com.instalego.dto.BankRequest;
import com.instalego.model.Bank;
import com.instalego.repository.BankRepository;
import com.instalego.repository.BankTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class BankService {

    private final BankRepository bankRepository;
    private final BankTemplateRepository bankTemplateRepository;

    public Bank createBank(BankRequest request) {
        if (bankRepository.findAll().stream().anyMatch(b -> b.getName().equalsIgnoreCase(request.getName()))) {
            throw new IllegalArgumentException("Bank with name '" + request.getName() + "' already exists");
        }
        Bank bank = new Bank();
        bank.setName(request.getName());
        Bank saved = bankRepository.save(bank);
        log.info("Created bank: id={}, name={}", saved.getId(), saved.getName());
        return saved;
    }

    public List<Bank> getAllBanks() {
        return bankRepository.findAll();
    }

    public List<Bank> getBanksWithActiveTemplate() {
        List<Bank> allBanks = bankRepository.findAll();
        return allBanks.stream()
                .filter(bank -> bankTemplateRepository.existsByBankId(bank.getId()))
                .toList();
    }

    public Bank getBankById(Long id) {
        return bankRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Bank not found with id: " + id));
    }
}
