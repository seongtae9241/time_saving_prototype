package com.timesavings.repo;

import com.timesavings.domain.CategorySuggestion;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CategorySuggestionRepo extends JpaRepository<CategorySuggestion, Long> {
    List<CategorySuggestion> findByUserIdOrderByIdDesc(Long userId);
    List<CategorySuggestion> findByNameKey(String nameKey);
}
