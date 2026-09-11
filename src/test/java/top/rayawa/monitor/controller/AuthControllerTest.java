package top.rayawa.monitor.controller;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import top.rayawa.monitor.service.AuthService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthControllerTest {

    private final AuthController controller = new AuthController(new AuthService(), "test-share-token");

    @Test
    void validShareTokenCreatesMonitorSessionAndRedirectsHome() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.loginFromShare("test-share-token", request, response);

        assertThat(request.getSession(false)).isNotNull();
        assertThat(request.getSession(false).getAttribute(AuthService.SESSION_USER)).isEqualTo("monitor");
        assertThat(response.getRedirectedUrl()).isEqualTo("/");
    }

    @Test
    void invalidShareTokenIsRejectedWithoutCreatingSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> controller.loginFromShare("wrong-token", request, response))
                .isInstanceOf(SecurityException.class)
                .hasMessage("分享链接无效");
        assertThat(request.getSession(false)).isNull();
    }
}
