package com.timesavings.repo;

import com.timesavings.domain.UploadFile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UploadFileRepo extends JpaRepository<UploadFile, String> {
}
