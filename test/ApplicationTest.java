import org.junit.jupiter.api.Test;
import play.mvc.Http.Response;
import play.test.FunctionalTest;

class ApplicationTest extends FunctionalTest {

    @Test
    void testThatIndexPageWorks() {
        Response response = GET("/");
        assertIsOk(response);
        assertContentType("text/html", response);
        assertCharset(play.Play.defaultWebEncoding, response);
    }
    
}