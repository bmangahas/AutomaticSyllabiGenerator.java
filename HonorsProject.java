package bmangahas;

// Import necessary libraries
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.docs.v1.Docs; // Google Docs API
import com.google.api.services.docs.v1.model.BatchUpdateDocumentRequest;
import com.google.api.services.docs.v1.model.Request;
import com.google.api.services.docs.v1.model.ReplaceAllTextRequest;
import com.google.api.services.docs.v1.model.SubstringMatchCriteria;
import com.google.api.services.drive.Drive; // Google Drive API
import com.google.api.services.drive.model.File;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import com.google.api.client.util.store.FileDataStoreFactory;

import java.io.*;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class HonorsProject {

    // Google API variables
    private static final String APPLICATION_NAME = "SMCC Syllabi Generator";
    private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
    private static final List<String> SCOPES = Arrays.asList(
            "https://www.googleapis.com/auth/drive",
            "https://www.googleapis.com/auth/documents"
    );
    private static Drive driveService; // Drive service instance
    private static Docs docsService; // Docs service instance

    // Google Docs template document ID
    private static final String TEMPLATE_DOCUMENT_ID = "1AGmEW1DW6YuMZiLwquv-T7GLlFSSzmu8GE6JWPdn2ow";

    // Course class to store details of each course
    static class Course {
        String name;
        String number;
        String location;
        String delivery;
        String days;
        String dates;
        String times;
        String instructors;
        String credits;

        Course(String name, String number, String location, String delivery, String days, String dates, String times, String instructors) {
            this.name = cleanCourseName(name); // Call method to 
            this.number = number;
            this.location = location;
            this.delivery = delivery;
            this.days = days;
            this.dates = dates;
            this.times = times;
            this.instructors = instructors;
            this.credits = extractCredits(name); // Call method to extract credits
        }
        // Helper method to remove the credits part from the course name
        private static String cleanCourseName(String courseName) {
            String[] parts = courseName.split(" – ");
            return parts[0]; // Return only the name part before – 3 credits
        }
        // Method to extract credits from course name
        private static String extractCredits(String courseName) {
            String credits = "";
            String[] parts = courseName.split(" – ");
            if (parts.length > 1) {
                String[] creditsParts = parts[1].split(" ");
                credits = creditsParts[0]; // Gets number before credits
            }
            return credits;
        }
    }

    public static void main(String[] args) {
        try {
            ArrayList<Course> allCourses = scrapeCourses();
            initializeGoogleServices();
            createGoogleDocsForCourses(allCourses);

        } catch (IOException | GeneralSecurityException e) {
            e.printStackTrace();
        }
    }

    // Method to initialize Google Drive and Docs services
    private static void initializeGoogleServices() throws IOException, GeneralSecurityException {
        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new FileReader("src/main/resources/client_secret.json")); // Adjust path as necessary

        // Use FileDataStoreFactory for storing credentials
        FileDataStoreFactory dataStoreFactory = new FileDataStoreFactory(new java.io.File("tokens"));
        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                GoogleNetHttpTransport.newTrustedTransport(), JSON_FACTORY, clientSecrets, SCOPES)
                .setDataStoreFactory(dataStoreFactory)
                .setAccessType("offline")
                .build();

        Credential credential = new AuthorizationCodeInstalledApp(flow, new LocalServerReceiver()).authorize("user");

        // Initialize Google Drive and Docs services with the acquired credential
        driveService = new Drive.Builder(GoogleNetHttpTransport.newTrustedTransport(), JSON_FACTORY, credential)
                .setApplicationName(APPLICATION_NAME)
                .build();
        docsService = new Docs.Builder(GoogleNetHttpTransport.newTrustedTransport(), JSON_FACTORY, credential)
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    // Scrape course data from the catalog
    private static ArrayList<Course> scrapeCourses() throws IOException {
        ArrayList<Course> allCourses = new ArrayList<>();
        String url = "https://classes.sis.maricopa.edu/?all_classes=true&terms%5B%5D=4246&institutions%5B%5D=SMC07&credit_career=B&credits_min=gte0&credits_max=lte9";
        Document doc = Jsoup.connect(url).get();
        Elements classContent = doc.select(".class-specs.odd, .class-specs.even");
        Elements className = doc.getElementsByTag("h3");
        int minSize = Math.min(className.size(), classContent.size());

        for (int i = 0; i < minSize; i++) {
            String courseName = className.get(i).text();
            Element classInfo = classContent.get(i);

            String classNumber = classInfo.getElementsByClass("class-number").text();
            String classLocation = classInfo.getElementsByClass("class-location").text();
            String classDelivery = classInfo.getElementsByClass("class-delivery").text();
            String classDays = classInfo.getElementsByClass("class-days").text();
            String classDates = classInfo.getElementsByClass("class-dates").text();
            String classTimes = classInfo.getElementsByClass("class-times").text();
            String classInstructors = classInfo.getElementsByClass("class-instructors").text();

            // Only add classes that have teachers
            if (!classInstructors.equalsIgnoreCase("Staff")) {
                allCourses.add(new Course(courseName, classNumber, classLocation, classDelivery, classDays, classDates, classTimes, classInstructors));
            }
        }
        return allCourses;
    }

    // Method to create a separate Google Doc for each course
    private static void createGoogleDocsForCourses(List<Course> courses) throws IOException {
        if (courses.isEmpty()) {
            System.out.println("No courses found with assigned instructors.");
            return;
        }

        for (Course course : courses) {
            File copiedFile = new File();
            copiedFile.setName("Syllabus - " + course.name);
            File docFile = driveService.files().copy(TEMPLATE_DOCUMENT_ID, copiedFile).setFields("id").execute();
            String documentId = docFile.getId();

            List<Request> requests = new ArrayList<>();

            // Replace placeholders with course data, including credits
            requests.add(new Request()
                    .setReplaceAllText(new ReplaceAllTextRequest()
                            .setContainsText(new SubstringMatchCriteria().setText("{{courseName}}").setMatchCase(true))
                            .setReplaceText(course.name)));
            requests.add(new Request()
                    .setReplaceAllText(new ReplaceAllTextRequest()
                            .setContainsText(new SubstringMatchCriteria().setText("{{courseNumber}}").setMatchCase(true))
                            .setReplaceText(course.number)));
            requests.add(new Request()
                    .setReplaceAllText(new ReplaceAllTextRequest()
                            .setContainsText(new SubstringMatchCriteria().setText("{{location}}").setMatchCase(true))
                            .setReplaceText(course.location)));
            requests.add(new Request()
                    .setReplaceAllText(new ReplaceAllTextRequest()
                            .setContainsText(new SubstringMatchCriteria().setText("{{delivery}}").setMatchCase(true))
                            .setReplaceText(course.delivery)));
            requests.add(new Request()
                    .setReplaceAllText(new ReplaceAllTextRequest()
                            .setContainsText(new SubstringMatchCriteria().setText("{{days}}").setMatchCase(true))
                            .setReplaceText(course.days)));
            requests.add(new Request()
                    .setReplaceAllText(new ReplaceAllTextRequest()
                            .setContainsText(new SubstringMatchCriteria().setText("{{dates}}").setMatchCase(true))
                            .setReplaceText(course.dates)));
            requests.add(new Request()
                    .setReplaceAllText(new ReplaceAllTextRequest()
                            .setContainsText(new SubstringMatchCriteria().setText("{{times}}").setMatchCase(true))
                            .setReplaceText(course.times)));
            requests.add(new Request()
                    .setReplaceAllText(new ReplaceAllTextRequest()
                            .setContainsText(new SubstringMatchCriteria().setText("{{instructors}}").setMatchCase(true))
                            .setReplaceText(course.instructors)));
            requests.add(new Request()
                    .setReplaceAllText(new ReplaceAllTextRequest()
                            .setContainsText(new SubstringMatchCriteria().setText("{{#}}").setMatchCase(true))
                            .setReplaceText(course.credits)));

            BatchUpdateDocumentRequest body = new BatchUpdateDocumentRequest().setRequests(requests);
            docsService.documents().batchUpdate(documentId, body).execute();

            System.out.println("Google Doc created for course " + course.name + ": https://docs.google.com/document/d/" + documentId);
        }
    }
}
