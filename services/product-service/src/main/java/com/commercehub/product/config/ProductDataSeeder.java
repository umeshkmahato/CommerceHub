package com.commercehub.product.config;

import com.commercehub.product.entity.Category;
import com.commercehub.product.entity.Product;
import com.commercehub.product.repository.CategoryRepository;
import com.commercehub.product.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Demo catalog seeder — runs once on startup, ONLY if the DB is empty.
 * Guarded by count()==0 so existing data is never touched or duplicated
 * (safe on every restart and on top of real data).
 */
@Component
public class ProductDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProductDataSeeder.class);

    private final CategoryRepository categoryRepo;
    private final ProductRepository productRepo;

    public ProductDataSeeder(CategoryRepository categoryRepo, ProductRepository productRepo) {
        this.categoryRepo = categoryRepo;
        this.productRepo = productRepo;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (categoryRepo.count() > 0 || productRepo.count() > 0) {
            log.info("Seed skipped — catalog already populated");
            return;
        }

        Category electronics = cat("Electronics", "electronics", "Phones, laptops, audio");
        Category home = cat("Home & Kitchen", "home-kitchen", "Appliances and cookware");
        Category sports = cat("Sports & Outdoors", "sports", "Fitness and camping gear");
        Category books = cat("Books", "books", "Tech and business reads");

        product("ELEC-001", "Aurora X5 Smartphone", electronics, "brand-new 5G phone",
                "Aurora", "899.00", "1099.00", "phone");
        product("ELEC-002", "Nebula Book Pro 14\"", electronics, "thin-and-light laptop",
                "Nebula", "1299.00", null, "laptop");
        product("ELEC-003", "PulseBuds ANC", electronics, "noise-cancelling earbuds",
                "Pulse", "149.00", "199.00", "audio");
        product("ELEC-004", "VoltCharge 65W GaN", electronics, "dual-port fast charger",
                "Volt", "39.99", null, "charger");
        product("HOME-001", "BrewMaster Espresso", home, "15-bar pump espresso machine",
                "BrewMaster", "249.00", null, "coffee");
        product("HOME-002", "AeroFry 5.0L", home, "oil-free air fryer",
                "Aero", "119.00", "149.00", "kitchen");
        product("SPRT-001", "TrailBlazer Tent 4P", sports, "all-weather 4-person tent",
                "Trail", "189.00", null, "camping");
        product("SPRT-002", "FlexFit Yoga Mat", sports, "6mm non-slip mat",
                "Flex", "29.99", null, "fitness");
        product("BOOK-001", "Designing Data-Intensive Apps", books,
                "Kleppmann's distributed systems classic", "O'Reilly", "44.99", null, "book");
        product("BOOK-002", "The Pragmatic Programmer", books,
                "Hunt & Thomas software craft", "Addison-Wesley", "39.95", null, "book");

        log.info("Seeded {} categories and {} products",
                categoryRepo.count(), productRepo.count());
    }

    private Category cat(String name, String slug, String desc) {
        Category c = new Category();
        c.setName(name); c.setSlug(slug); c.setDescription(desc);
        return categoryRepo.save(c);
    }

    private void product(String sku, String name, Category cat, String desc,
                         String brand, String price, String compareAt, String img) {
        Product p = new Product();
        p.setSku(sku); p.setName(name); p.setDescription(desc);
        p.setBrand(brand); p.setCategory(cat);
        p.setPrice(new BigDecimal(price));
        if (compareAt != null) p.setCompareAtPrice(new BigDecimal(compareAt));
        p.setCostPrice(new BigDecimal(price).multiply(new BigDecimal("0.6")));
        p.setStatus(Product.Status.ACTIVE);
        p.setImageUrl("https://picsum.photos/seed/" + img + "/600/400");
        productRepo.save(p);
    }
}
