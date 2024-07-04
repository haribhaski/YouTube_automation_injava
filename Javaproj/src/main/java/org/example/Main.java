package org.example;

import org.openqa.selenium.*;
import org.openqa.selenium.chrome.*;
import org.openqa.selenium.support.ui.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.*;

public class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(Main::createAndShowGUI);
    }

    private static void createAndShowGUI() {
        JFrame frame = new JFrame("YouTube Sentiment Analysis");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(400, 200);
        frame.setLayout(new BorderLayout());

        JPanel panel = new JPanel(new BorderLayout());
        JTextField searchField = new JTextField();
        JButton submitButton = new JButton("Submit");

        panel.add(new JLabel("Enter your search query:"), BorderLayout.NORTH);
        panel.add(searchField, BorderLayout.CENTER);
        panel.add(submitButton, BorderLayout.SOUTH);

        frame.add(panel, BorderLayout.CENTER);

        submitButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String searchQuery = searchField.getText();
                if (!searchQuery.trim().isEmpty()) {
                    new Thread(() -> startProcess(searchQuery)).start();
                } else {
                    JOptionPane.showMessageDialog(frame, "Please enter a search query", "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        frame.setVisible(true);
    }

    private static void startProcess(String searchQuery) {
        WebDriver headlessDriver = null;
        WebDriver chromeDriver = null;
        try {
            // Headless driver to analyze comments and description
            ChromeOptions headlessOptions = new ChromeOptions();
            headlessOptions.addArguments("--headless", "--disable-gpu", "--disable-dev-shm-usage", "--no-sandbox", "--disable-software-rasterizer", "--window-size=1920,1080", "--blink-settings=imagesEnabled=false", "--proxy-server=direct://", "--dns-prefetch-disable");
            headlessDriver = new ChromeDriver(headlessOptions);

            String searchUrl = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(searchQuery, StandardCharsets.UTF_8) + "&sp=EgIQAQ%253D%253D"; // Adding filter parameter to include only videos

            headlessDriver.get(searchUrl);
            WebDriverWait headlessWait = new WebDriverWait(headlessDriver, Duration.ofSeconds(20));

            // Scroll down to load more videos
            for (int i = 0; i < 3; i++) { // Adjust the number of scrolls as needed
                ((JavascriptExecutor) headlessDriver).executeScript("window.scrollTo(0, document.body.scrollHeight)");
                Thread.sleep(2000); // Wait for the page to load
            }

            WebElement firstVideoElement = headlessWait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("a#video-title")));
            String firstVideoUrl = firstVideoElement.getAttribute("href");
            System.out.println("First video's URL: " + firstVideoUrl);

            // Navigate to the video page
            headlessDriver.get(firstVideoUrl);
            boolean isShort = firstVideoUrl.contains("short");

            // Analyze comments on the headless driver
            analyzeCommentsAndDescription(headlessDriver, 5, isShort);

            ChromeOptions chromeOptions = new ChromeOptions();
            chromeOptions.addArguments("--disable-gpu");
            chromeDriver = new ChromeDriver(chromeOptions);
            chromeDriver.get(firstVideoUrl);

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (headlessDriver != null) {
                headlessDriver.quit();
            }
        }
    }

    public static void analyzeCommentsAndDescription(WebDriver driver, int numberOfCommentsToRead, boolean isShort) {
        try {
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(20));

            String descriptionText = "";
            if (!isShort) {
                WebElement descriptionElement = wait.until(ExpectedConditions.presenceOfElementLocated(By.xpath("//ytd-text-inline-expander[@id='description-inline-expander']")));
                descriptionText = descriptionElement.getText();
                System.out.println("Description: " + descriptionText);

                String descriptionSentiment = analyzeSentiment(descriptionText);
                System.out.println("Description sentiment: " + descriptionSentiment);
            }

            AtomicInteger positiveCount = new AtomicInteger(0);
            AtomicInteger negativeCount = new AtomicInteger(0);
            AtomicInteger neutralCount = new AtomicInteger(0);

            WebElement commentSection;
            if (isShort) {
                commentSection = wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("#content")));
                System.out.println("Comment section for shorts loaded.");
            }
            else {
                commentSection = wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("ytd-comments")));
                System.out.println("Comment section for regular video loaded.");
            }
            ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView(true);", commentSection);

            for (int retries = 0; retries < 5; retries++){
                ((JavascriptExecutor) driver).executeScript("window.scrollBy(0, 500);");
                Thread.sleep(2000);
            }

            List<WebElement> commentElements;
            if (isShort){
                commentElements = driver.findElements(By.cssSelector("#contents"));
            }
            else{
                commentElements = driver.findElements(By.cssSelector("#content-text"));
            }

            if (commentElements.isEmpty()){
                System.out.println("No comments found.");
                return;
            }

            int numberOfComments = Math.min(numberOfCommentsToRead, commentElements.size());
            System.out.println("Number of comments to be read: " + numberOfComments);

            ExecutorService threadPool = Executors.newFixedThreadPool(numberOfComments);
            for (int i = 0; i < numberOfComments; i++) {
                List<WebElement> singleCommentList = Collections.singletonList(commentElements.get(i));
                threadPool.execute(new CommentReaderThread(singleCommentList, positiveCount, negativeCount, neutralCount));
            }

            threadPool.shutdown();
            threadPool.awaitTermination(5, TimeUnit.MINUTES);

            int total = positiveCount.get() + negativeCount.get() + neutralCount.get();
            double positivePercentage = (double) positiveCount.get() / total * 100;
            double negativePercentage = (double) negativeCount.get() / total * 100;
            double neutralPercentage = (double) neutralCount.get() / total * 100;

            JOptionPane.showMessageDialog(null,
                    String.format("Sentiment Analysis Results:\n\nPositive: %.2f%%\nNegative: %.2f%%\nNeutral: %.2f%%",
                            positivePercentage, negativePercentage, neutralPercentage),
                    "Sentiment Analysis Results", JOptionPane.INFORMATION_MESSAGE);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static class CommentReaderThread implements Runnable {
        List<WebElement> commentElements;
        AtomicInteger positiveCount;
        AtomicInteger negativeCount;
        AtomicInteger neutralCount;

        public CommentReaderThread(List<WebElement> commentElements, AtomicInteger positiveCount, AtomicInteger negativeCount, AtomicInteger neutralCount) {
            this.commentElements = commentElements;
            this.positiveCount = positiveCount;
            this.negativeCount = negativeCount;
            this.neutralCount = neutralCount;
        }

        @Override
        public void run() {
            try {
                for (WebElement comment : commentElements) {
                    String commentText = comment.getText();
                    // Perform sentiment analysis and update counters
                    String sentiment = analyzeSentiment(commentText);
                    if (sentiment.equals("Positive")) {
                        positiveCount.incrementAndGet();
                    } else if (sentiment.equals("Negative")) {
                        negativeCount.incrementAndGet();
                    } else {
                        neutralCount.incrementAndGet();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static String analyzeSentiment(String text) {
        List<String> positiveWords = Arrays.asList(
                "great", "good", "awesome", "excellent", "fantastic", "amazing", "love", "liked", "superb", "wonderful", "incredible", "perfect", "enjoyed", "cool", "nice", "brilliant", "funny", "hilarious", "helpful", "best", "loved it", "thumbs up", "recommend", "entertaining", "calming", "informative", "engaging", "well done", "beautiful", "inspiring", "professional", "high quality", "well made", "outstanding", "mind blowing", "excellent job", "learned a lot", "subscribe", "subscribed", "liked it", "impressed", "phenomenal", "epic", "awesome video", "great work", "favorite", "fantastic job", "soothing", "valuable", "insightful", "bravo", "kudos", "top notch", "marvelous", "splendid", "terrific", "stellar", "genius", "exceptional", "captivating", "grateful", "thank you", "appreciate", "love this", "great quality", "enjoyable", "very nice", "excellent content", "highly recommend", "fun", "super informative", "well executed", "very professional", "kept me hooked", "admirable", "exceptionally good", "charming", "very creative", "skillful", "enthralling", "fantastic editing", "crisp and clear", "vibrant", "great visuals", "super engaging", "very detailed", "impeccable", "perfectly done", "satisfying", "heartwarming", "very talented", "original", "superb quality", "very appealing", "excellent production",
                "engrossing", "masterpiece", "thought provoking", "pure art", "amazing experience", "so much fun", "extraordinary", "riveting", "commendable", "top tier", "astounding", "super fun", "always great", "nice touch", "well thought", "great pace", "wonderful storyline", "fun adventure", "brilliantly executed", "hats off", "bravo", "superb work", "amazing job", "so cool", "highly entertaining", "effortless", "solid work", "genius", "masterful", "artistic", "breathtaking", "cool", "delightful", "detailed", "dynamic", "engaging", "enthralling", "fascinating", "flawless", "glorious", "heartfelt", "imaginative", "incomparable", "insightful", "inspirational", "intelligent", "intriguing", "majestic", "magnificent", "mesmerizing", "moving", "outstanding", "phenomenal", "pleasing", "remarkable", "refreshing", "spectacular", "stunning", "superior", "thoughtful", "touching", "unique", "unmatched", "vivid", "well acted", "well crafted", "well designed", "well directed", "well written", "worth watching", "must watch", "riveting", "captivating", "enthusiastic", "love it", "pure joy", "brilliant cinematography", "gorgeous visuals", "superb storytelling", "memorable", "powerful", "soulful", "poignant", "excellent pacing", "great sound", "fantastic music", "impressive acting", "well-produced", "top-notch production", "great effects",
                "perfect casting", "heartwarming", "uplifting", "positive message", "funny as hell", "laugh out loud", "so much fun", "very engaging", "exciting", "entertaining as always", "non stop fun", "absolutely hilarious", "fun to watch", "so relatable", "incredibly well done", "highly creative", "perfectly executed", "great energy", "loved every minute", "totally recommend", "worth every second", "amazing visuals", "well-paced", "nicely shot", "excellent cinematography", "awesome direction", "brilliant direction", "great direction", "superb direction", "fantastic direction", "amazing direction", "beautiful direction", "brilliant acting", "superb acting", "fantastic acting", "amazing acting", "excellent acting", "great acting", "incredible acting", "wonderful acting", "awesome acting", "brilliant writing", "superb writing", "fantastic writing", "amazing writing", "excellent writing", "great writing", "incredible writing", "wonderful writing", "awesome writing","wow","melody","music","heart","educational","beautiful","unrivaled" , "top"
        );
        List<String> negativeWords = Arrays.asList(
                "bad", "poor", "worst", "terrible", "disappointing", "boring", "hate", "horrible", "awful", "lame", "dumb", "annoying", "dislike", "waste", "useless", "clickbait", "unsubscribed", "stupid", "cringe", "not good", "thumbs down", "sucks", "offensive", "pathetic", "cheap", "unwatchable", "dreadful", "horrid", "mediocre", "flawed", "terribly done", "uninteresting", "confusing", "pointless", "tedious", "uninspired", "frustrating", "inaccurate", "misleading", "disgusting", "rude", "disrespectful", "hate this", "garbage", "trash", "low quality", "bad quality", "not worth it", "time wasted", "very bad", "big letdown", "not impressed", "terrible content", "poorly made", "substandard", "badly done", "poorly executed", "fails", "big fail", "unprofessional", "disturbing", "off-putting", "won't watch again", "unsubscribe","lol",
                "lag", "buffering", "sound issues", "bad audio", "bad video", "glitchy", "poor editing", "bad editing", "very disappointing", "horribly executed", "pathetically done", "trash content", "worst ever", "never again", "can't stand this", "gave up", "awkward", "terribly bad", "unbearable", "horrible acting", "poor performance", "very lame", "terrible script", "bad visuals", "waste of time", "low effort", "poor camera work", "dull", "cheap production", "bad storyline", "irritating", "clumsy", "failed to deliver", "flat", "very poor", "flop", "incomplete", "confusing plot", "overhyped", "dissatisfied", "not funny", "displeasing", "very annoying", "offensive content", "inappropriate", "nonsense", "unfunny", "unsatisfactory", "horrendous", "laughable", "insulting", "atrocious", "distasteful", "unpleasant", "bored", "unsubscribing", "subpar", "disgraceful", "unacceptable", "don't like it", "no value", "spam", "childish", "repetitive", "unengaging", "awkward moments", "dismal", "underwhelming", "yawn", "pointless content", "overrated", "couldn't finish", "flawed concept",
                "boring as hell", "painful to watch", "poorly acted", "bad execution", "very bad quality", "cringe-worthy", "irritated", "tediously boring", "awful experience", "too slow", "too fast", "horrible direction", "bad direction", "worst direction", "terrible direction", "awful direction", "pathetic direction", "unprofessional direction", "bad acting", "worst acting", "terrible acting", "awful acting", "pathetic acting", "unprofessional acting", "bad writing", "worst writing", "terrible writing", "awful writing", "pathetic writing", "unprofessional writing", "not engaging", "too noisy", "confusing editing", "badly edited", "overly long", "very short", "inconsistent", "poor production", "amateurish", "unconvincing", "shoddy", "boring story", "uninteresting story", "poor sound quality", "poor visual quality", "disturbing content", "irritating sound", "horrible visuals", "bland", "horrible effects", "poor effects", "worst effects", "terrible effects", "awful effects", "pathetic effects", "unprofessional effects", "bad soundtrack", "worst soundtrack", "terrible soundtrack", "awful soundtrack", "pathetic soundtrack", "unprofessional soundtrack", "bad music", "worst"
        );


        text = text.toLowerCase();

        for (String word : positiveWords) {
            if (text.contains(word.toLowerCase())) {
                return "Positive";
            }
        }

        for (String word : negativeWords) {
            if (text.contains(word.toLowerCase())) {
                return "Negative";
            }
        }

        return "Neutral";
    }
}