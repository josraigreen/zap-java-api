package net.continuumsecurity.proxy;


import edu.umass.cs.benchlab.har.HarEntry;
import edu.umass.cs.benchlab.har.HarRequest;
import edu.umass.cs.benchlab.har.HarResponse;
import org.junit.*;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.remote.CapabilityType;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.zaproxy.clientapi.core.Alert;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;

public class ZAProxyScannerTest {
    static WebDriver driver;
    static ZAProxyScanner zaproxy;
    static String HOST = "127.0.0.1";
    static int PORT = 8888;
    static String CHROME = "src/test/resources/chromedriver-mac";
    static String BASEURL = "http://localhost:9090/";

    @BeforeClass
    public static void configure() throws Exception {
        zaproxy = new ZAProxyScanner(HOST, PORT, "");
        DesiredCapabilities capabilities = new DesiredCapabilities();
        capabilities.setCapability(CapabilityType.PROXY, zaproxy.getSeleniumProxy());

        System.setProperty("webdriver.chrome.driver", CHROME);
        driver = new ChromeDriver(capabilities);
        driver.manage().timeouts().implicitlyWait(5, TimeUnit.SECONDS);

    }

    @AfterClass
    public static void tearDown() throws Exception {
        driver.close();
    }

    @Before
    public void setup() throws ProxyException {
        zaproxy.clear();
        driver.manage().deleteAllCookies();
    }

    @Test
    public void testGetXmlReport() throws ProxyException {
        String report = new String(zaproxy.getXmlReport());
        assert report.startsWith("<?xml version=\"1.0\"");
        assert report.endsWith("</OWASPZAPReport>");
    }

    @Test
    public void testGetHtmlReport() throws ProxyException {
        String report = new String(zaproxy.getHtmlReport()).trim();
        assert report.startsWith("<html>");
        assert report.endsWith("</html>");
    }

    @Test
    public void testGetHistory() throws ProxyException {
        driver.get(BASEURL);
        List<HarEntry> history = zaproxy.getHistory();
        assertThat(history.size(), greaterThan(0));
        Assert.assertEquals(history.get(0).getResponse().getStatus(), 302);
    }

    @Test
    public void testMakeRequest() throws IOException {
        driver.get(BASEURL + "task/search?q=test&search=Search");
        HarRequest origRequest = zaproxy.getHistory().get(0).getRequest();
        HarResponse origResponse = zaproxy.getHistory().get(0).getResponse();
        List<HarEntry> responses = zaproxy.makeRequest(origRequest, true);
        HarResponse manualResponse = responses.get(0).getResponse();

        Assert.assertEquals(origResponse.getBodySize(), manualResponse.getBodySize());
        Assert.assertEquals(origResponse.getContent().getText(), manualResponse.getContent().getText());
    }

    @Test
    public void testCookiesWithMakeRequest() throws IOException {
        System.out.println("Opening login page");
        openLoginPage();
        System.out.println("Logging on");

        login("bob", "password");        //sets a session ID cookie

        String sessionID = driver.manage().getCookieNamed("JSESSIONID").getValue();
        assert sessionID.length() > 4;
        System.out.println("getting history");
        List<HarEntry> history = zaproxy.getHistory();
        System.out.println("clearing history");
        zaproxy.clear();
        System.out.println("cleared");
        HarRequest copy = history.get(history.size() - 1).getRequest(); //The last request will contain a session ID
        copy = HarUtils.changeCookieValue(copy, "JSESSIONID", "nothing");

        List<HarEntry> responses = zaproxy.makeRequest(copy, true);
        //The changed session ID
        assertThat(responses.get(0).getRequest().getCookies().getCookies().get(0).getValue(), equalTo("nothing"));
    }

    @Test
    public void testSimpleActiveScanWorkflow() throws InterruptedException {
        zaproxy.setEnablePassiveScan(false);
        System.out.println("Opening login page");
        openLoginPage();
        System.out.println("Logging on");

        login("bob", "password");
        zaproxy.setEnableScanners("40018",true);
        zaproxy.deleteAlerts();
        zaproxy.scan(BASEURL);
        int scanId = zaproxy.getLastScannerScanId();
        int status = zaproxy.getScanProgress(scanId);
        while (status < 100) {
            Thread.sleep(2000);
            status = zaproxy.getScanProgress(scanId);
            System.out.println("Scan: "+status);
        }
        List<Alert> alerts = zaproxy.getAlerts();
        assertThat(alerts.size(), greaterThan(0));

        //Repeat after deleting alerts
        zaproxy.deleteAlerts();
        zaproxy.scan(BASEURL);
        scanId = zaproxy.getLastScannerScanId();
        status = zaproxy.getScanProgress(scanId);
        while (status < 100) {
            Thread.sleep(2000);
            status = zaproxy.getScanProgress(scanId);
            System.out.println("Scan: "+status);
        }
        List<Alert> secondBatchAlerts = zaproxy.getAlerts();
        assertThat(secondBatchAlerts.size(), greaterThan(0));
        assertThat(secondBatchAlerts.size(), equalTo(alerts.size()));
    }


    private Map<String, List<Alert>> getAlertsByHost(List<Alert> alerts) {

        Map<String, List<Alert>> alertsByHost = new HashMap<String, List<Alert>>();
        for (Alert alert : alerts) {
            URL url = null;
            try {
                url = new URL(alert.getUrl());
                String host = url.getHost();
                if (alertsByHost.get(host) == null) {
                    alertsByHost.put(host, new ArrayList<Alert>());
                }
                alertsByHost.get(host).add(alert);
            } catch (MalformedURLException e) {
                System.err.println("Skipping malformed URL: "+alert.getUrl());
                e.printStackTrace();
            }
        }
        return alertsByHost;
    }


    public void openLoginPage() {
        driver.get(BASEURL + "user/login");
    }

    public void login(String user, String pass) {
        driver.findElement(By.id("username")).clear();
        driver.findElement(By.id("username")).sendKeys(user);
        driver.findElement(By.id("password")).clear();
        driver.findElement(By.id("password")).sendKeys(pass);
        driver.findElement(By.name("_action_login")).click();
    }

}
