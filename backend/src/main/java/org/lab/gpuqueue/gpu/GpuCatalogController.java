package org.lab.gpuqueue.gpu;

import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class GpuCatalogController {
    private final JdbcClient jdbc;

    GpuCatalogController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/gpus")
    ResponseEntity<List<Gpu>> listGpus() {
        try {
            var gpus = jdbc.sql("SELECT id, model FROM gpus ORDER BY id")
                    .query((row, index) -> new Gpu(row.getString("id"), row.getString("model")))
                    .list();
            return ResponseEntity.ok(gpus);
        } catch (DataAccessException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    record Gpu(String id, String model) {}
}
