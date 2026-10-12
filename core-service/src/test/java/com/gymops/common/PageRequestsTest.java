package com.gymops.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gymops.common.api.PageRequests;
import com.gymops.common.exception.BusinessRuleException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

class PageRequestsTest {

    @Test
    void parsesWhitelistedSort() {
        var pageable = PageRequests.of(1, 20, "lastName,desc", Set.of("lastName"), Sort.unsorted());
        assertThat(pageable.getSort().getOrderFor("lastName").getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(pageable.getPageNumber()).isEqualTo(1);
    }

    @Test
    void rejectsUnknownSortAndOversizedPages() {
        assertThatThrownBy(() -> PageRequests.of(0, 20, "passwordHash", Set.of("lastName"), Sort.unsorted()))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> PageRequests.of(0, 500, null, Set.of(), Sort.unsorted()))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> PageRequests.of(-1, 10, null, Set.of(), Sort.unsorted()))
                .isInstanceOf(BusinessRuleException.class);
    }
}
