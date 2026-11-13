package com.commercehub.inventory.config;

import com.commercehub.inventory.entity.StockLevel;
import com.commercehub.inventory.repository.StockLevelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demo stock seeder — same idempotent contract as ProductDataSeeder:
 * runs only when inventorydb is empty. Product IDs 1..10 match the
 * product-service seed order on a fresh DB; stale/extra IDs simply get
 * no stock row (stock is created lazily elsewhere if needed).
 */
@Component
public class InventoryDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InventoryDataSeeder.class);

    private static final String[] SKUS = {
            "ELEC-001", "ELEC-002", "ELEC-003", "ELEC-004",
            "HOME-001", "HOME-002", "SPRT-001", "SPRT-002",
            "BOOK-001", "BOOK-002"
    };
    private static final int[] STOCK = {25, 12, 80, 60, 15, 22, 9, 40, 35, 50};

    private final StockLevelRepository stockRepo;

    public InventoryDataSeeder(StockLevelRepository stockRepo) {
        this.stockRepo = stockRepo;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (stockRepo.count() > 0) {
            log.info("Seed skipped — inventory already populated");
            return;
        }
        for (int i = 0; i < SKUS.length; i++) {
            StockLevel s = new StockLevel();
            s.setProductId((long) (i + 1));
            s.setSku(SKUS[i]);
            s.setQuantityOnHand(STOCK[i]);
            s.setReorderPoint(5);
            stockRepo.save(s);
        }
        log.info("Seeded {} stock levels", SKUS.length);
    }
}
