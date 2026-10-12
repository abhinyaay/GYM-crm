package com.gymops.complaint;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ComplaintCommentRepository extends JpaRepository<ComplaintComment, Long> {

    List<ComplaintComment> findByComplaintIdOrderByCreatedAtAsc(Long complaintId);

    List<ComplaintComment> findByComplaintIdAndInternalFalseOrderByCreatedAtAsc(Long complaintId);
}
