package com.commercehub.inventory;

import com.commercehub.inventory.entity.StockLevel;
import com.commercehub.inventory.repository.StockLevelRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StockLevel repository IT — real MySQL 8.4. Verifies the generated
 * `quantity_available` column and the pessimistic `findForUpdate` lock
 * actually work against the production engine (H2 would silently differ).
 */
@Testcontainers
@SpringBootTest
class StockLevelRepositoryIT {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("inventorydb").withUsername("root").withPassword("root");

    @Autowired StockLevelRepository stockRepo;

    private StockLevel newStock(Long productId, int onHand) {
        StockLevel s = new StockLevel();
        s.setProductId(productId);
        s.setSku("IT-" + productId);
        s.setQuantityOnHand(onHand);
        return s;
    }

    @Test
    @Transactional   // keep test data isolated; FOR UPDATE needs a tx anyway
    void generatedAvailableColumn_reflectsOnHandMinusReserved() {
        StockLevel s = stockRepo.saveAndFlush(newStock(9001L, 10));
        assertEquals(10, s.available());
        assertEquals(10, s.getQuantityAvailable());   // generated column value

        s.reserve(4);
        stockRepo.saveAndFlush(s);
        StockLevel reloaded = stockRepo.findById(s.getId()).orElseThrow();
        assertEquals(6, reloaded.getQuantityAvailable());
        assertEquals(4, reloaded.getQuantityReserved());
    }

    @Test
    @Transactional
    void findForUpdate_locksRowWithinTransaction() {
        stockRepo.saveAndFlush(newStock(9002L, 5));
        List<StockLevel> locked = stockRepo.findForUpdate(9002L, "WH-01");
        assertEquals(1, locked.size());
        assertEquals("WH-01", locked.get(0).getWarehouseCode());
    }

    @Test
    @Transactional
    void reserve_beyondAvailable_throws() {
        StockLevel s = stockRepo.saveAndFlush(newStock(9003L, 2));
        assertThrows(IllegalStateException.class, () -> s.reserve(3));
    }
}
