package edu.utexas.tacc.tapis.kubernetes;

import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.testng.Assert;
import org.testng.annotations.AfterSuite;
import org.testng.annotations.BeforeSuite;
import org.testng.annotations.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;


/**
 *
 */
@Test(groups={"integration"})
public class KubernetesTest
{
    // nested classes


    /**
     *
     */
    private enum HttpMethod {
        GET,
        POST,
        PUT,
        DELETE
    };

    /**
     *
     */
    private static class HttpException extends Exception {

        private final int _statusCode;


        /**
         *
         * @param code
         * @param message
         */
        private HttpException(int code, String message)
        {
            super(message);

            _statusCode = code;
        }


        /**
         *
         * @param code
         * @param source
         * @return
         * @throws IOException
         */
        public static HttpException createInstance(int code, Object source) throws IOException
        {
            String message;

            if (source instanceof String)
                message = (String) source;
            else if (source instanceof InputStream)
                message = new String(((InputStream) source).readAllBytes());
            else
                message = null;

            return new HttpException(code, message);
        }

        /**
         *
         * @return
         */
        public int getStatusCode()
        {
            return _statusCode;
        }
    }

    /**
     *
     */
    @FunctionalInterface
    private interface JobStatusHandler
    {
        /**
         *
         * @param status
         * @param jobUuid
         * @return
         * @throws HttpException
         * @throws InterruptedException
         * @throws IOException
         */
        boolean handleStatus(String status, String jobUuid) throws HttpException, IOException, InterruptedException;
    }

    /**
     *
     */
    private class FinishWaiting implements JobStatusHandler
    {
        @Override
        public boolean handleStatus(String status, String jobUuid) throws HttpException, IOException, InterruptedException
        {
            if (status.equals("FINISHED") || status.equals("FAILED") || status.equals("CANCELLED")) {
                int count = getJobOutputCount(jobUuid);

                if (count > 0) {
                    String filename = downloadJobOutput(jobUuid);

                    System.out.println("saved output for job " + jobUuid + " to " + filename);
                }

                return true;
            }

            return false;
        }
    }

    /**
     *
     */
    private class CancelJob implements JobStatusHandler
    {
        @Override
        public boolean handleStatus(String status, String jobUuid) throws HttpException, IOException, InterruptedException
        {
            if (status.equals("RUNNING"))
                cancelJob(jobUuid);

            return false;
        }
    }


    // data fields


    private String _execSystemId;
    private String _tapisUrlBase;
    private String _token;
    private List<String> _appIds;


    // public methods


    /**
     *
     * @throws HttpException
     * @throws InterruptedException
     * @throws IOException
     */
    @BeforeSuite
    public void setup() throws HttpException, IOException, InterruptedException
    {
        _execSystemId = System.getenv("TAPIS_EXEC_SYSTEM_ID");
        _tapisUrlBase = System.getenv("TAPIS_URL_BASE");

        if (_tapisUrlBase == null)
            _tapisUrlBase = "http://localhost";

        String username = System.getenv("TAPIS_USERNAME");
        String password = System.getenv("TAPIS_PASSWORD");

        _token = getToken(username, password);
        _appIds = new ArrayList<String>();
    }

    /**
     *
     * @throws HttpException
     * @throws InterruptedException
     * @throws IOException
     */
    @AfterSuite
    public void teardown() throws HttpException, IOException, InterruptedException
    {
        for (String appId : _appIds)
            setAppDeleteState(appId, true);
    }

    /**
     *
     * @throws HttpException
     * @throws InterruptedException
     * @throws IOException
     */
    @Test
    public void createAppTest() throws HttpException, IOException, InterruptedException
    {
        runCreateAppTest("sleep_app.json");
        runCreateAppTest("mpi_pi_app.json");
    }

    /**
     *
     * @throws HttpException
     * @throws InterruptedException
     * @throws IOException
     */
    @Test (dependsOnMethods="createAppTest")
    public void submitJobTest() throws HttpException, IOException, InterruptedException
    {
        runSubmitJobTest("sleep_job.json", "FINISHED", new FinishWaiting());
        runSubmitJobTest("mpi_pi_job.json", "FINISHED", new FinishWaiting());
    }

    /**
     *
     * @throws HttpException
     * @throws InterruptedException
     * @throws IOException
     */
    @Test (dependsOnMethods="createAppTest")
    public void cancelJobTest() throws HttpException, IOException, InterruptedException
    {
        runSubmitJobTest("sleep_cancel_job.json", "CANCELLED", new CancelJob(), new FinishWaiting());
        runSubmitJobTest("mpi_pi_job.json", "CANCELLED", new CancelJob(), new FinishWaiting());
    }

    /**
     *
     * @throws HttpException
     * @throws InterruptedException
     * @throws IOException
     */
    @Test (dependsOnMethods="createAppTest")
    public void failJobTest() throws HttpException, IOException, InterruptedException
    {
        runSubmitJobTest("sleep_fail_job.json", "FAILED", new FinishWaiting());
    }


    // private methods


    /**
     *
     * @param name
     * @return
     * @throws IOException
     */
    private String readResource(String name) throws IOException
    {
        try (InputStream inStream = KubernetesTest.class.getClassLoader().getResourceAsStream(name)) {
            String result = new String(inStream.readAllBytes());

            return result;
        }
    }

    /**
     *
     * @param username
     * @param password
     * @return
     * @throws HttpException
     * @throws InterruptedException
     * @throws IOException
     */
    private String getToken(String username, String password) throws HttpException, IOException, InterruptedException
    {
        Map<String, String> headers = new TreeMap<String, String>();

        headers.put("Content-type", "application/json");

        StringBuilder body = new StringBuilder();

        body.append("{\"username\":\"");
        body.append(username);
        body.append("\",\"password\":\"");
        body.append(password);
        body.append("\",\"grant_type\":\"password\"}");

        String response = getResponse("oauth2/tokens", headers, HttpMethod.POST, body.toString());
        JsonNode root = (new ObjectMapper()).readTree(response);
        String status = root.at("/status").asText();
        String token;

        if (status.equals("success"))
            token = root.at("/result/access_token/access_token").asText();
        else {
            String filename = username + "_token.json";

            try (FileWriter writer = new FileWriter(filename)) {
                writer.write(response);
            }

            System.out.println("token generation status for user " + username + " is " + status + ", response written to " + filename);

            token = null;
        }

        return token;
    }

    /**
     *
     * @param name
     * @throws HttpException
     * @throws InterruptedException
     * @throws IOException
     */
    private void runCreateAppTest(String name) throws HttpException, IOException, InterruptedException
    {
        String config = readResource(name);
        JsonNode root = (new ObjectMapper()).readTree(config);
        String appId = root.at("/id").asText();
        String appVersion = root.at("/version").asText();

        System.out.println("running add application test using " + appId + ", version " + appVersion);

        String status;

        try {
            status = createApp(config);
        }
        catch (HttpException err) {
            if (err.getStatusCode() != 409)
                throw err;

            status = setAppDeleteState(appId, false);
        }

        Assert.assertEquals(status, "success");

        _appIds.add(appId);
    }

    /**
     *
     * @param config
     * @return
     * @throws HttpException
     * @throws InterruptedException
     * @throws IOException
     */
    private String createApp(String config) throws HttpException, IOException, InterruptedException
    {
        String body = config.replaceAll("\\$\\{EXEC_SYSTEM_ID\\}", _execSystemId);
        Map<String, String> headers = new TreeMap<String, String>();

        headers.put("X-Tapis-Token", _token);
        headers.put("Content-type", "application/json");

        String response = getResponse("apps", headers, HttpMethod.POST, body);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(response);
        String status = root.at("/status").asText();

        root = mapper.readTree(body);

        String appId = root.at("/id").asText();
        String appVersion = root.at("/version").asText();

        System.out.println("created app " + appId + ", version " + appVersion + ", status is " + status);

        if (status.equals("success")) {
            String appConfig = getApp(appId, appVersion);
            String filename = appId + "_" + appVersion + "_config.json";

            try (FileWriter writer = new FileWriter(filename)) {
                writer.write(appConfig);
            }

            System.out.println("configuration for app " + appId + ", version " + appVersion + " written to " + filename);
        }
        else {
            String filename = appId + "_" + appVersion + "_create.json";

            try (FileWriter writer = new FileWriter(filename)) {
                writer.write(response);
            }

            System.out.println("response written to " + filename);
        }

        return status;
    }

    /**
     *
     * @param appId
     * @param appVersion
     * @return
     * @throws HttpException
     * @throws InterruptedException
     * @throws IOException
     */
    private String getApp(String appId, String appVersion) throws HttpException, IOException, InterruptedException
    {
        StringBuilder path = new StringBuilder();

        path.append("apps/");
        path.append(appId);
        path.append('/');
        path.append(appVersion);

        Map<String, String> headers = new TreeMap<String, String>();

        headers.put("X-Tapis-Token", _token);

        return getResponse(path.toString(), headers, HttpMethod.GET, null);
    }

    /**
     *
     * @param appId
     * @param delete
     * @return
     * @throws HttpException
     * @throws InterruptedException
     * @throws IOException
     */
    private String setAppDeleteState(String appId, boolean delete) throws HttpException, IOException, InterruptedException
    {
        String action = delete ? "delete" : "undelete";
        StringBuilder path = new StringBuilder();

        path.append("apps/");
        path.append(appId);
        path.append('/');
        path.append(action);

        Map<String, String> headers = new TreeMap<String, String>();

        headers.put("X-Tapis-Token", _token);

        String response = getResponse(path.toString(), headers, HttpMethod.POST, null);
        JsonNode root = (new ObjectMapper()).readTree(response);
        String status = root.at("/status").asText();

        System.out.println(action + "d app " + appId + ", status is " + status);

        if (!status.equals("success")) {
            String filename = appId + "_" + action + ".json";

            try (FileWriter writer = new FileWriter(filename)) {
                writer.write(response);
            }

            System.out.println("response written to " + filename);
        }

        return status;
    }

    /**
     *
     * @param name
     * @param expected
     * @param handlers
     * @throws HttpException
     * @throws InterruptedException
     * @throws IOException
     */
    private void runSubmitJobTest(String name, String expected, JobStatusHandler... handlers) throws HttpException, IOException, InterruptedException
    {
        String config = readResource(name);
        JsonNode root = (new ObjectMapper()).readTree(config);
        String jobName = root.at("/name").asText();

        System.out.println("running submit job test using " + jobName);

        String jobUuid = submitJob(config);

        Assert.assertNotNull(jobUuid);

        String status = waitForJobStatus(jobUuid, handlers);

        Assert.assertEquals(status, expected);
    }

    /**
     *
     * @param config
     * @return
     * @throws HttpException
     * @throws InterruptedException
     * @throws IOException
     */
    private String submitJob(String config) throws HttpException, IOException, InterruptedException
    {
        Map<String, String> headers = new TreeMap<String, String>();

        headers.put("X-Tapis-Token", _token);
        headers.put("Content-type", "application/json");

        String response = getResponse("jobs/submit", headers, HttpMethod.POST, config);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(response);
        String status = root.at("/status").asText();
        String jobUuid;
        String jobId;

        if (status.equals("success")) {
            jobUuid = root.at("/result/uuid").asText();
            jobId = jobUuid;
        }
        else {
            root = mapper.readTree(config);
            jobId = root.at("/name").asText();
            jobUuid = null;
        }

        String filename = jobId + "_submit.json";

        try (FileWriter writer = new FileWriter(filename)) {
            writer.write(response);
        }

        System.out.println("submitted job " + jobId + ", status is " + status + ", response written to " + filename);

        return jobUuid;
    }

    /**
     *
     * @param jobUuid
     * @return
     * @throws HttpException
     * @throws InterruptedException
     * @throws IOException
     */
    private String cancelJob(String jobUuid) throws HttpException, IOException, InterruptedException
    {
        StringBuilder path = new StringBuilder();

        path.append("jobs/");
        path.append(jobUuid);
        path.append("/cancel");

        Map<String, String> headers = new TreeMap<String, String>();

        headers.put("X-Tapis-Token", _token);

        String response = getResponse(path.toString(), headers, HttpMethod.POST, null);
        JsonNode root = (new ObjectMapper()).readTree(response);
        String status = root.at("/status").asText();

        System.out.println("cancelled job " + jobUuid + ", status is " + status);

        if (!status.equals("success")) {
            String filename = jobUuid + "_cancel.json";

            try (FileWriter writer = new FileWriter(filename)) {
                writer.write(response);
            }

            System.out.println("response written to " + filename);
        }

        return status;
    }

    /**
     *
     * @param jobUuid
     * @param handlers
     * @return
     * @throws HttpException
     * @throws InterruptedException
     * @throws IOException
     */
    private String waitForJobStatus(String jobUuid, JobStatusHandler... handlers) throws HttpException, IOException, InterruptedException
    {
        StringBuilder pathBuilder = new StringBuilder();

        pathBuilder.append("jobs/");
        pathBuilder.append(jobUuid);
        pathBuilder.append("/status");

        String path = pathBuilder.toString();
        Map<String, String> headers = new TreeMap<String, String>();

        headers.put("X-Tapis-Token", _token);

        ObjectMapper mapper = new ObjectMapper();
        String status;

        System.out.println("waiting for status for job " + jobUuid + "...");

        while (true) {
            Thread.sleep(5000);

            String response = getResponse(path, headers, HttpMethod.GET, null);
            JsonNode root = mapper.readTree(response);

            status = root.at("/result/status").asText();

            System.out.println("status is " + status);

            boolean terminate = false;

            for (JobStatusHandler handler : handlers) {
                if (handler.handleStatus(status, jobUuid))
                    terminate = true;
            }

            if (terminate) {
                String filename = jobUuid + "_status.json";

                try (FileWriter writer = new FileWriter(filename)) {
                    writer.write(response);
                }

                System.out.println("finished waiting for status for job " + jobUuid + ", response written to " + filename);

                break;
            }
        }

        return status;
    }

    /**
     *
     * @param jobUuid
     * @return
     * @throws HttpException
     * @throws InterruptedException
     * @throws IOException
     */
    private int getJobOutputCount(String jobUuid) throws HttpException, IOException, InterruptedException
    {
        StringBuilder path = new StringBuilder();

        path.append("jobs/");
        path.append(jobUuid);
        path.append("/output/list/");

        Map<String, String> headers = new TreeMap<String, String>();

        headers.put("X-Tapis-Token", _token);

        String response = getResponse(path.toString(), headers, HttpMethod.GET, null);
        JsonNode root = (new ObjectMapper()).readTree(response);
        String status = root.at("/status").asText();
        int count;

        if (status.equals("success"))
            count = root.at("/metadata/recordCount").asInt();
        else
            count = 0;

        return count;
    }

    /**
     *
     * @param jobUuid
     * @return
     * @throws HttpException
     * @throws InterruptedException
     * @throws IOException
     */
    private String downloadJobOutput(String jobUuid) throws HttpException, IOException, InterruptedException
    {
        StringBuilder path = new StringBuilder();

        path.append("jobs/");
        path.append(jobUuid);
        path.append("/output/download/?compress=true&format=zip");

        Map<String, String> headers = new TreeMap<String, String>();

        headers.put("X-Tapis-Token", _token);

        String filename = jobUuid + "_output.zip";

        downloadFile(path.toString(), headers, HttpMethod.GET, null, filename);

        return filename;
    }

    /**
     *
     * @param path
     * @param headers
     * @param method
     * @param body
     * @return
     * @throws HttpException
     * @throws InterruptedException
     * @throws IOException
     */
    private String getResponse(String path, Map<String, String> headers, HttpMethod method, String body) throws HttpException, IOException, InterruptedException
    {
        return getHttpResponse(path, headers, method, body, HttpResponse.BodyHandlers.ofString());
    }

    /**
     *
     * @param path
     * @param headers
     * @param method
     * @param body
     * @param filename
     * @throws HttpException
     * @throws InterruptedException
     * @throws IOException
     */
    private void downloadFile(String path, Map<String, String> headers, HttpMethod method, String body, String filename) throws HttpException, IOException, InterruptedException
    {
        InputStream inStream = getHttpResponse(path, headers, method, body, HttpResponse.BodyHandlers.ofInputStream());

        try (FileOutputStream outStream = new FileOutputStream(filename)) {
            byte[] readBuffer = new byte[8192];
            int bytesRead;

            while ((bytesRead = inStream.read(readBuffer, 0, readBuffer.length)) >= 0)
                outStream.write(readBuffer, 0, bytesRead);
        }
    }

    /**
     *
     * @param <T>
     * @param path
     * @param headers
     * @param method
     * @param body
     * @param handler
     * @return
     * @throws HttpException
     * @throws InterruptedException
     * @throws IOException
     */
    private <T> T getHttpResponse(String path, Map<String, String> headers, HttpMethod method, String body, HttpResponse.BodyHandler<T> handler) throws HttpException, IOException, InterruptedException
    {
        String url = _tapisUrlBase + "/v3/" + path;
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url));

        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet())
                builder.header(entry.getKey(), entry.getValue());
        }

        if (method == HttpMethod.DELETE)
            builder.DELETE();
        else if (method != HttpMethod.GET) {
            HttpRequest.BodyPublisher publisher;

            if (body != null)
                publisher = HttpRequest.BodyPublishers.ofString(body);
            else
                publisher = HttpRequest.BodyPublishers.noBody();

            if (method == HttpMethod.POST)
                builder.POST(publisher);
            else if (method == HttpMethod.PUT)
                builder.PUT(publisher);
        }

        HttpRequest request = builder.build();
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<T> response = client.send(request, handler);

        if (response.statusCode() >= 300)
            throw HttpException.createInstance(response.statusCode(), response.body());

        return response.body();
    }
}
