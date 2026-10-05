package com.spotifyyoutube.migrator.migration.api;

import com.spotifyyoutube.migrator.migration.api.dto.CreateMigrationRequest;
import com.spotifyyoutube.migrator.migration.api.dto.MigrationJobResponse;
import com.spotifyyoutube.migrator.migration.api.dto.MigrationTaskResponse;
import com.spotifyyoutube.migrator.migration.api.mapper.MigrationMapper;
import com.spotifyyoutube.migrator.migration.application.MigrationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/migrations")
public class MigrationController {

    private final MigrationService migrationService;

    public MigrationController(MigrationService migrationService) {
        this.migrationService = migrationService;
    }

    @PostMapping
    public ResponseEntity<MigrationJobResponse> createMigration(@RequestBody @Valid CreateMigrationRequest request) {
        var job = migrationService.createMigrationJob(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(MigrationMapper.toJobResponse(job));
    }

    @GetMapping("/{id}")
    public ResponseEntity<MigrationJobResponse> getMigration(@PathVariable UUID id) {
        var job = migrationService.getMigrationJobById(id);
        return ResponseEntity.ok(MigrationMapper.toJobResponse(job));
    }

    @GetMapping("/{id}/tasks")
    public ResponseEntity<List<MigrationTaskResponse>> getMigrationTasks(@PathVariable UUID id) {
        var tasks = migrationService.getMigrationTasks(id);
        return ResponseEntity.ok(MigrationMapper.toTaskResponseList(tasks));
    }
}
