package com.timesavings.repo;

import com.timesavings.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface MessageRepo extends JpaRepository<Message, Long> {
    List<Message> findByRequestIdOrderByIdAsc(Long requestId);
}
