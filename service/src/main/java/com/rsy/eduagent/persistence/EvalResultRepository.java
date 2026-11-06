package com.rsy.eduagent.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvalResultRepository extends JpaRepository<EvalResult, Long> {
    List<EvalResult> findByRunIdOrderByIdAsc(Long runId);
}
