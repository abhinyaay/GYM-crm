package com.gymops.member;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gymops.common.api.PageResponse;
import com.gymops.config.AppProperties;
import com.gymops.config.ClockConfig;
import com.gymops.member.dto.MemberResponse;
import com.gymops.security.JwtService;
import com.gymops.security.RateLimiter;
import com.gymops.security.RestAuthenticationEntryPoint;
import com.gymops.security.SecurityConfig;
import com.gymops.security.UserPrincipal;
import com.gymops.subscription.dto.MembershipSummary;
import com.gymops.user.RoleName;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Web-layer slice: request validation, error envelope, authentication and role checks. */
@WebMvcTest(MemberController.class)
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class, JwtService.class, ClockConfig.class})
@EnableConfigurationProperties(AppProperties.class)
@TestPropertySource(locations = "classpath:application-test-slice.properties")
class MemberControllerTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @MockitoBean MemberService memberService;
    @MockitoBean RateLimiter rateLimiter;

    private String token(RoleName role, Set<Long> branches, Long memberId) {
        return "Bearer " + jwtService.issueAccessToken(new UserPrincipal(1L, "u@example.com", Set.of(role), branches,
                memberId, null)).token();
    }

    private static final String VALID_MEMBER = """
            {"homeBranchId":1,"firstName":"Asha","lastName":"Rao","email":"asha@example.com","phone":"+91 9000000000"}
            """;

    @Test
    void unauthenticatedRequestGetsStandard401() throws Exception {
        mvc.perform(get("/api/v1/members"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        String token = token(RoleName.RECEPTIONIST, Set.of(1L), null);
        mvc.perform(get("/api/v1/members").header("Authorization", token.substring(0, token.length() - 4) + "abcd"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    void memberRoleCannotSearchMembers() throws Exception {
        mvc.perform(get("/api/v1/members").header("Authorization", token(RoleName.MEMBER, Set.of(), 9L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void invalidBodyReturnsFieldViolations() throws Exception {
        mvc.perform(post("/api/v1/members").header("Authorization", token(RoleName.RECEPTIONIST, Set.of(1L), null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"\",\"email\":\"not-an-email\",\"phone\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[*].field", hasItem("email")))
                .andExpect(jsonPath("$.violations[*].field", hasItem("homeBranchId")));
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        mvc.perform(post("/api/v1/members").header("Authorization", token(RoleName.RECEPTIONIST, Set.of(1L), null))
                        .contentType(MediaType.APPLICATION_JSON).content("{oops"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void receptionistRegistersMemberWith201AndLocation() throws Exception {
        when(memberService.register(any())).thenReturn(new MemberResponse(42L, "M001042", null, 1L, "Branch", "Asha", "Rao",
                "asha@example.com", "+91 9000000000", null, null, null, null, null, null, MemberStatus.ACTIVE,
                Instant.now(), null, List.of(), MembershipSummary.none(), 0));
        mvc.perform(post("/api/v1/members").header("Authorization", token(RoleName.RECEPTIONIST, Set.of(1L), null))
                        .header("X-Request-Id", "test-correlation-1")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_MEMBER))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/members/42"))
                .andExpect(header().string("X-Request-Id", "test-correlation-1"))
                .andExpect(jsonPath("$.memberCode").value("M001042"));
    }

    @Test
    void trainerCannotRegisterMembers() throws Exception {
        mvc.perform(post("/api/v1/members").header("Authorization", token(RoleName.TRAINER, Set.of(1L), null))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_MEMBER))
                .andExpect(status().isForbidden());
    }

    @Test
    void wrongContentTypeIs415NotAServerError() throws Exception {
        mvc.perform(post("/api/v1/members").header("Authorization", token(RoleName.RECEPTIONIST, Set.of(1L), null))
                        .contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void unacceptableAcceptHeaderIs406() throws Exception {
        when(memberService.search(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0, true));
        mvc.perform(get("/api/v1/members").header("Authorization", token(RoleName.MANAGER, Set.of(1L), null))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotAcceptable());
    }

    @Test
    void unsupportedSortIsRejected() throws Exception {
        mvc.perform(get("/api/v1/members?sort=passwordHash").header("Authorization",
                        token(RoleName.MANAGER, Set.of(1L), null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
