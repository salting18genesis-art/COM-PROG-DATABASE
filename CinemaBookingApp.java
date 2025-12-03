import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Main application class (CinemaBookingApp.java).
 * Contains all static utilities, database handling, and the main entry point.
 * This class is modified to ensure the database connection is reliably managed
 * for the multi-step GUI process.
 */
public class CinemaBookingApp {

    // Database constants and shared state
    private static final String DB_URL = "jdbc:sqlite:cinema.db";
    private static Connection conn = null; // Stored globally
    private static int currentCustomerId = -1; // Used for tracking the current reservation ID

    // Store movie data loaded from DB (Title @ Time -> MovieData)
    private static Map<String, MovieData> movieDetails = new HashMap<>();

    public static void main(String[] args) {
        try {
            // 1. Initialize Database
            initializeDatabase();
            
            // Add a shutdown hook to close the connection safely when the JVM exits
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    if (conn != null && !conn.isClosed()) {
                        conn.close();
                        System.out.println("Database connection successfully closed.");
                    }
                } catch (SQLException e) {
                    System.err.println("Error closing database connection: " + e.getMessage());
                }
            }));

            // 2. Load movie data for dropdowns
            loadMovieData();

            // 3. Launch the initial Movie Selection GUI on the Event Dispatch Thread (EDT)
            SwingUtilities.invokeLater(() -> new MovieSelectionForm());

        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "Application Initialization Error: " + e.getMessage(),
                                          "Fatal Error", JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        }
    }

    // =================DATABASE INITIALIZATION & UTILS=================

    /**
     * Data class to hold movie information retrieved from the database.
     */
    static class MovieData {
        int id;
        String title;
        String showTime;
        double price;
        int layoutRows;
        int layoutCols;

        public MovieData(int id, String title, String showTime, double price, int rows, int cols) {
            this.id = id;
            this.title = title;
            this.showTime = showTime;
            this.price = price;
            this.layoutRows = rows;
            this.layoutCols = cols;
        }
    }

    private static void initializeDatabase() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC Driver not found. Please add the library.", e);
        }

        // Establish the single, global connection
        conn = DriverManager.getConnection(DB_URL);
        
        try (Statement stmt = conn.createStatement()) {
            // 1. Customers/Reservation Holder (Simplified for Queue ID)
            String sqlCustomers = "CREATE TABLE IF NOT EXISTS Customers (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "queue_id TEXT NOT NULL UNIQUE)";
            stmt.execute(sqlCustomers);

            // 2. Movies Table (Contains configuration)
            String sqlMovies = "CREATE TABLE IF NOT EXISTS Movies (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "title TEXT NOT NULL," +
                    "show_time TEXT NOT NULL," +
                    "price REAL," +
                    "layout_rows INTEGER," +
                    "layout_cols INTEGER," +
                    "UNIQUE(title, show_time))";
            stmt.execute(sqlMovies);

            // 3. Reservations Table (Booking actual seats)
            String sqlReservations = "CREATE TABLE IF NOT EXISTS Reservations (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "movie_id INTEGER," +
                    "customer_id INTEGER," +
                    "seat_row_idx INTEGER," +
                    "seat_col_idx INTEGER," +
                    "FOREIGN KEY(movie_id) REFERENCES Movies(id)," +
                    "FOREIGN KEY(customer_id) REFERENCES Customers(id)," +
                    "UNIQUE(movie_id, seat_row_idx, seat_col_idx))";
            stmt.execute(sqlReservations);

            // Seed dummy movies/show times (Fixed ticket price 350.00)
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM Movies");
            rs.next();
            if (rs.getInt(1) == 0) {
                double fixedPrice = 350.00;
                // Movie 1: Avengers Endgame (5x10)
                stmt.execute(String.format("INSERT INTO Movies (title, show_time, price, layout_rows, layout_cols) VALUES ('Avengers Endgame', '2:30 PM', %.2f, 5, 10)", fixedPrice));
                stmt.execute(String.format("INSERT INTO Movies (title, show_time, price, layout_rows, layout_cols) VALUES ('Avengers Endgame', '6:30 PM', %.2f, 5, 10)", fixedPrice));
                // Movie 2: Star Wars (6x8)
                stmt.execute(String.format("INSERT INTO Movies (title, show_time, price, layout_rows, layout_cols) VALUES ('Star Wars: The Force Awakens', '4:00 PM', %.2f, 6, 8)", fixedPrice));
                // Movie 3: Minecraft the Movie (4x6)
                stmt.execute(String.format("INSERT INTO Movies (title, show_time, price, layout_rows, layout_cols) VALUES ('Minecraft: The Movie', '1:00 PM', %.2f, 4, 6)", fixedPrice));
            }
        }
    }

    private static void loadMovieData() throws SQLException {
        movieDetails.clear();
        String sql = "SELECT id, title, show_time, price, layout_rows, layout_cols FROM Movies ORDER BY title, show_time";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                MovieData data = new MovieData(
                    rs.getInt("id"),
                    rs.getString("title"),
                    rs.getString("show_time"),
                    rs.getDouble("price"),
                    rs.getInt("layout_rows"),
                    rs.getInt("layout_cols")
                );
                // Key format: "Title @ Time"
                movieDetails.put(data.title + " @ " + data.showTime, data);
            }
        }
    }

    // Static getter/setter methods for sharing state between GUI classes
    public static Connection getConnection() { 
        try {
            if (conn == null || conn.isClosed()) {
                // Attempt to re-establish if it was somehow closed
                conn = DriverManager.getConnection(DB_URL);
            }
        } catch (SQLException e) {
            System.err.println("Attempt to restore connection failed: " + e.getMessage());
            return null; 
        }
        return conn; 
    }
    public static Map<String, MovieData> getMovieDetails() { return movieDetails; }
    public static void setCurrentCustomerId(int id) { currentCustomerId = id; }
    public static int getCurrentCustomerId() { return currentCustomerId; }
}


/**
 * Class 2: The initial form for selecting Movie and Show Time, and generating the Queue ID.
 */
class MovieSelectionForm extends JFrame {

    private final JComboBox<String> movieComboBox;
    private final JComboBox<String> timeComboBox;
    private final JLabel queueIdLabel;
    private final AtomicInteger customerCounter = new AtomicInteger(0);

    public MovieSelectionForm() {
        super("Cinema Ticket Request");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(10, 10, 10, 10);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // --- 1. Queue System Initialization ---
        JLabel queueTitle = new JLabel("QUEUE SYSTEM", JLabel.CENTER);
        queueTitle.setFont(new Font("SansSerif", Font.BOLD, 18));
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2; add(queueTitle, gbc);

        // Fetch the next available Queue ID
        String nextQueueId = getNextQueueId();
        queueIdLabel = new JLabel(nextQueueId, JLabel.LEFT);
        queueIdLabel.setFont(new Font("Monospaced", Font.BOLD, 16));

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1; add(new JLabel("Your Queue ID:"), gbc);
        gbc.gridx = 1; gbc.gridy = 1; add(queueIdLabel, gbc);

        // --- 2. Movie Selection Dropdowns ---
        JLabel selectionTitle = new JLabel("MOVIE & SHOWTIME SELECTION", JLabel.CENTER);
        selectionTitle.setFont(new Font("SansSerif", Font.BOLD, 18));
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2; add(selectionTitle, gbc);

        // Separate list of unique movie titles
        List<String> uniqueTitles = CinemaBookingApp.getMovieDetails().values().stream()
                .map(data -> data.title)
                .distinct()
                .sorted()
                .collect(java.util.stream.Collectors.toList());

        movieComboBox = new JComboBox<>(uniqueTitles.toArray(new String[0]));
        timeComboBox = new JComboBox<>();

        // Add action listener to update show times when movie changes
        movieComboBox.addActionListener(this::updateShowTimes);

        // Initial load of show times based on the first movie
        updateShowTimes(null);

        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 1; add(new JLabel("Select Movie:"), gbc);
        gbc.gridx = 1; gbc.gridy = 3; add(movieComboBox, gbc);

        gbc.gridx = 0; gbc.gridy = 4; add(new JLabel("Select Show Time:"), gbc);
        gbc.gridx = 1; gbc.gridy = 4; add(timeComboBox, gbc);

        // --- 3. Submit Button ---
        JButton submitButton = new JButton("Submit Selection");
        submitButton.addActionListener(this::submitSelection);
        gbc.gridx = 1; gbc.gridy = 5; gbc.gridwidth = 1; gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.EAST;
        add(submitButton, gbc);

        pack();
        setLocationRelativeTo(null);
        setVisible(true);
    }

    /**
     * Finds the highest existing queue number and returns the next one (e.g., A1 -> A2).
     */
    private String getNextQueueId() {
        int maxId = 0;
        // Extracts the number part after 'A' and finds the maximum
        String sql = "SELECT MAX(CAST(SUBSTR(queue_id, 2) AS INTEGER)) FROM Customers";

        try (Connection conn = CinemaBookingApp.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                maxId = rs.getInt(1);
            }
        } catch (SQLException e) {
            System.err.println("Warning: Could not fetch max queue ID (assuming 0): " + e.getMessage());
        }
        // Increment and return the next ID with prefix 'A'
        customerCounter.set(maxId);
        return "A" + customerCounter.incrementAndGet();
    }

    /**
     * Updates the timeComboBox options based on the selected movie title.
     */
    private void updateShowTimes(ActionEvent e) {
        String selectedMovie = (String) movieComboBox.getSelectedItem();
        timeComboBox.removeAllItems();

        if (selectedMovie == null) return;

        CinemaBookingApp.getMovieDetails().forEach((key, data) -> {
            if (data.title.equals(selectedMovie)) {
                timeComboBox.addItem(data.showTime);
            }
        });
    }

    /**
     * Handles the submission, saves the Queue ID, and moves to the summary form.
     */
    private void submitSelection(ActionEvent event) {
        String queueId = queueIdLabel.getText();
        String selectedMovie = (String) movieComboBox.getSelectedItem();
        String selectedTime = (String) timeComboBox.getSelectedItem();

        if (selectedMovie == null || selectedTime == null) {
            JOptionPane.showMessageDialog(this, "Please select both a movie and a show time.", "Selection Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        String movieKey = selectedMovie + " @ " + selectedTime;
        CinemaBookingApp.MovieData movieData = CinemaBookingApp.getMovieDetails().get(movieKey);

        if (movieData == null) {
            JOptionPane.showMessageDialog(this, "Internal error: Movie data not found.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        Connection conn = CinemaBookingApp.getConnection();
        if (conn == null) {
            JOptionPane.showMessageDialog(this, "Database connection is not available.", "DB Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        PreparedStatement pstmt = null;
        try {
            // Save the Queue ID to the database and get the generated customer ID
            String insertSql = "INSERT INTO Customers(queue_id) VALUES(?)";
            pstmt = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS);

            pstmt.setString(1, queueId);
            pstmt.executeUpdate();

            ResultSet rs = pstmt.getGeneratedKeys();
            if (rs.next()) {
                int customerId = rs.getInt(1);
                CinemaBookingApp.setCurrentCustomerId(customerId);

                // Proceed to Movie Summary
                dispose();
                new MovieSummaryForm(movieData, queueId);
            } else {
                 JOptionPane.showMessageDialog(this, "Failed to generate customer ID.", "DB Error", JOptionPane.ERROR_MESSAGE);
            }
        } catch (SQLException e) {
             JOptionPane.showMessageDialog(this, "Database Error during queue generation: " + e.getMessage(), "SQL Error", JOptionPane.ERROR_MESSAGE);
        } finally {
            if (pstmt != null) {
                try { pstmt.close(); } catch (SQLException e) { /* ignored */ }
            }
        }
    }
}


/**
 * Class 3: Displays the summary of the selection before seat booking.
 */
class MovieSummaryForm extends JFrame {

    private final CinemaBookingApp.MovieData movieData;
    private final String queueId;

    public MovieSummaryForm(CinemaBookingApp.MovieData movieData, String queueId) {
        super("Reservation Summary");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        this.movieData = movieData;
        this.queueId = queueId;

        setLayout(new BorderLayout(10, 10));

        // --- Header Panel ---
        JPanel headerPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        JLabel headerLabel = new JLabel("YOUR RESERVATION DETAILS", JLabel.CENTER);
        headerLabel.setFont(new Font("SansSerif", Font.BOLD, 20));
        headerPanel.add(headerLabel);
        add(headerPanel, BorderLayout.NORTH);


        // --- Summary Grid Panel ---
        JPanel summaryPanel = new JPanel(new GridLayout(5, 2, 10, 10));
        summaryPanel.setBorder(BorderFactory.createEmptyBorder(20, 30, 20, 30));

        // Queue ID
        summaryPanel.add(new JLabel("Queue ID:"));
        JLabel queueLabel = new JLabel(queueId);
        queueLabel.setFont(new Font("Monospaced", Font.BOLD, 16));
        summaryPanel.add(queueLabel);

        // Movie
        summaryPanel.add(new JLabel("Movie:"));
        summaryPanel.add(new JLabel(movieData.title));

        // Show Time
        summaryPanel.add(new JLabel("Show Time:"));
        summaryPanel.add(new JLabel(movieData.showTime));

        // Ticket Price
        summaryPanel.add(new JLabel("Ticket Price (per seat):"));
        summaryPanel.add(new JLabel(String.format("₱%.2f", movieData.price)));

        // Seating Layout
        summaryPanel.add(new JLabel("Seating Layout:"));
        summaryPanel.add(new JLabel(String.format("%d rows x %d columns", movieData.layoutRows, movieData.layoutCols)));

        add(summaryPanel, BorderLayout.CENTER);


        // --- Footer Panel (Action Buttons) ---
        JPanel footerPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton proceedButton = new JButton("Proceed to Seat Selection");
        proceedButton.addActionListener(this::proceedToSeatSelection);
        footerPanel.add(proceedButton);

        add(footerPanel, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(null);
        setVisible(true);
    }

    private void proceedToSeatSelection(ActionEvent e) {
        dispose();
        new SeatMapForm(movieData);
    }
}


/**
 * Class 4: Handles Multi-Seat Selection, Total Price Calculation, and Booking Logic.
 */
class SeatMapForm extends JFrame {

    private final CinemaBookingApp.MovieData movieData;
    private final JButton[][] seatButtons;
    private final List<Seat> selectedSeats = new ArrayList<>();
    private final JLabel totalLabel;
    private final JButton confirmBookingButton;

    // Custom colors for seat states
    private static final Color AVAILABLE_COLOR = new Color(144, 238, 144); // Light Green
    private static final Color SELECTED_COLOR = new Color(255, 255, 102);  // Bright Yellow
    private static final Color RESERVED_COLOR = new Color(255, 99, 71); // Tomato Red

    /** Helper class to store row/column indices of a selected seat. */
    private static class Seat {
        final int rowIdx;
        final int colIdx;

        Seat(int r, int c) {
            this.rowIdx = r;
            this.colIdx = c;
        }

        // Returns seat name like "A1", "C5"
        String getName() {
            return Character.toString((char)('A' + rowIdx)) + (colIdx + 1);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Seat seat = (Seat) o;
            return rowIdx == seat.rowIdx && colIdx == seat.colIdx;
        }

        @Override
        public int hashCode() {
            return 31 * rowIdx + colIdx;
        }
    }

    public SeatMapForm(CinemaBookingApp.MovieData movieData) {
        super("Seat Map: " + movieData.title + " (" + movieData.showTime + ")");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        this.movieData = movieData;
        this.seatButtons = new JButton[movieData.layoutRows][movieData.layoutCols];

        setLayout(new BorderLayout(10, 10));

        // --- Screen Indicator ---
        JLabel screenLabel = new JLabel("--- SCREEN HERE ---", JLabel.CENTER);
        screenLabel.setFont(new Font("Monospaced", Font.BOLD, 14));
        screenLabel.setBorder(BorderFactory.createLineBorder(Color.BLACK, 2));
        add(screenLabel, BorderLayout.NORTH);

        // --- Seat Grid Panel (Same as before) ---
        JPanel seatPanel = new JPanel(new GridLayout(movieData.layoutRows + 1, movieData.layoutCols + 1, 5, 5));
        seatPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        seatPanel.add(new JLabel(" ")); // Top-left spacer
        for (int c = 0; c < movieData.layoutCols; c++) {
            seatPanel.add(new JLabel(String.valueOf(c + 1), JLabel.CENTER));
        }

        for (int r = 0; r < movieData.layoutRows; r++) {
            seatPanel.add(new JLabel(Character.toString((char)('A' + r)), JLabel.CENTER));

            for (int c = 0; c < movieData.layoutCols; c++) {
                final int dbRowIdx = r;
                final int dbColIdx = c;
                JButton seat = new JButton();
                seat.setPreferredSize(new Dimension(45, 45));
                seat.setMargin(new Insets(0, 0, 0, 0));
                seatButtons[r][c] = seat;

                // Change: Seat click now toggles selection, doesn't book immediately
                seat.addActionListener(e -> toggleSeatSelection(seat, dbRowIdx, dbColIdx));
                seatPanel.add(seat);
            }
        }
        add(seatPanel, BorderLayout.CENTER);

        // --- Footer Panel (Total Price & Booking) ---
        JPanel footerContainer = new JPanel(new BorderLayout());
        footerContainer.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));

        // Total Price Display
        JPanel totalPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        totalLabel = new JLabel("Total Price: ₱0.00");
        totalLabel.setFont(new Font("SansSerif", Font.BOLD, 16));
        totalPanel.add(totalLabel);
        footerContainer.add(totalPanel, BorderLayout.WEST);

        // Action Buttons (Confirm & Back)
        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        
        confirmBookingButton = new JButton("Confirm Booking (0 Seats)");
        confirmBookingButton.setEnabled(false); // Initially disabled
        confirmBookingButton.addActionListener(this::confirmBooking);
        actionPanel.add(confirmBookingButton);

        JButton backButton = new JButton("← Go Back to Summary");
        backButton.addActionListener(e -> { 
            dispose(); 
            String currentQueueId = getQueueId(CinemaBookingApp.getCurrentCustomerId());
            new MovieSummaryForm(movieData, currentQueueId != null ? currentQueueId : "A1"); 
        });
        actionPanel.add(backButton);
        
        footerContainer.add(actionPanel, BorderLayout.EAST);
        add(footerContainer, BorderLayout.SOUTH);

        loadSeatStatus();

        pack();
        setLocationRelativeTo(null);
        setVisible(true);
    }

    private String getQueueId(int customerId) {
        if (customerId == -1) return null;
        String id = null;
        String sql = "SELECT queue_id FROM Customers WHERE id = ?";
        Connection conn = CinemaBookingApp.getConnection();
        if (conn == null) return null;

        PreparedStatement pstmt = null;
        try {
            pstmt = conn.prepareStatement(sql);
            pstmt.setInt(1, customerId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                id = rs.getString("queue_id");
            }
            rs.close();
        } catch (SQLException e) {
            System.err.println("Error fetching Queue ID: " + e.getMessage());
        } finally {
            if (pstmt != null) {
                try { pstmt.close(); } catch (SQLException e) { /* ignored */ }
            }
        }
        return id;
    }

    /**
     * Toggles a seat between AVAILABLE and SELECTED state.
     */
    private void toggleSeatSelection(JButton seat, int r, int c) {
        Seat seatObj = new Seat(r, c);

        if (selectedSeats.contains(seatObj)) {
            // Deselect the seat
            selectedSeats.remove(seatObj);
            seat.setBackground(AVAILABLE_COLOR);
        } else {
            // Select the seat
            selectedSeats.add(seatObj);
            seat.setBackground(SELECTED_COLOR);
        }
        updateTotal();
    }

    /**
     * Updates the total price display and the confirmation button state.
     */
    private void updateTotal() {
        int count = selectedSeats.size();
        double totalPrice = count * movieData.price;
        
        totalLabel.setText(String.format("Total Price: ₱%.2f (for %d seats)", totalPrice, count));
        confirmBookingButton.setText(String.format("Confirm Booking (%d Seats)", count));
        confirmBookingButton.setEnabled(count > 0);
    }


    /**
     * Loads the status of all seats for the current movie and resets selected seats.
     */
    private void loadSeatStatus() {
        selectedSeats.clear();
        updateTotal();

        Connection conn = CinemaBookingApp.getConnection();
        if (conn == null) return;

        PreparedStatement resStmt = null;
        try {
            resStmt = conn.prepareStatement("SELECT seat_row_idx, seat_col_idx FROM Reservations WHERE movie_id = ?");
            resStmt.setInt(1, movieData.id);
            ResultSet resRs = resStmt.executeQuery();

            // Reset all seats to AVAILABLE state
            for (int r = 0; r < movieData.layoutRows; r++) {
                for (int c = 0; c < movieData.layoutCols; c++) {
                    seatButtons[r][c].setBackground(AVAILABLE_COLOR);
                    seatButtons[r][c].setEnabled(true);
                    seatButtons[r][c].setText(Character.toString((char)('A' + r)) + (c + 1));
                }
            }

            // Mark reserved seats
            while (resRs.next()) {
                int r = resRs.getInt("seat_row_idx");
                int c = resRs.getInt("seat_col_idx");
                if (r < movieData.layoutRows && c < movieData.layoutCols) {
                    seatButtons[r][c].setBackground(RESERVED_COLOR);
                    seatButtons[r][c].setEnabled(false);
                }
            }
            resRs.close();
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Failed to load seat map: " + e.getMessage(), "DB Error", JOptionPane.ERROR_MESSAGE);
        } finally {
            if (resStmt != null) {
                try { resStmt.close(); } catch (SQLException e) { /* ignored */ }
            }
        }
    }

    /**
     * Final action: Books all selected seats in a single database transaction.
     */
    private void confirmBooking(ActionEvent e) {
        if (selectedSeats.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please select at least one seat to proceed.", "Booking Error", JOptionPane.WARNING_MESSAGE);
            return;
        }

        double finalPrice = selectedSeats.size() * movieData.price;
        String seatList = selectedSeats.stream().map(Seat::getName).collect(Collectors.joining(", "));
        String currentQueueId = getQueueId(CinemaBookingApp.getCurrentCustomerId());

        int response = JOptionPane.showConfirmDialog(
            this,
            String.format("Confirm booking for %d seats (%s) totaling ₱%.2f?", selectedSeats.size(), seatList, finalPrice),
            "Final Confirmation",
            JOptionPane.YES_NO_OPTION
        );

        if (response != JOptionPane.YES_OPTION) {
            return;
        }

        Connection conn = CinemaBookingApp.getConnection();
        if (conn == null) {
            JOptionPane.showMessageDialog(this, "Database connection is not available.", "DB Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        PreparedStatement pstmt = null;
        try {
            // Start transaction: ensure all or none of the seats are booked
            conn.setAutoCommit(false); 

            String insertSql = "INSERT INTO Reservations (movie_id, customer_id, seat_row_idx, seat_col_idx) VALUES (?, ?, ?, ?)";
            pstmt = conn.prepareStatement(insertSql);

            for (Seat seat : selectedSeats) {
                pstmt.setInt(1, movieData.id);
                pstmt.setInt(2, CinemaBookingApp.getCurrentCustomerId());
                pstmt.setInt(3, seat.rowIdx);
                pstmt.setInt(4, seat.colIdx);
                pstmt.addBatch(); // Batch the insert statement
            }

            pstmt.executeBatch(); // Execute all inserts at once
            conn.commit();        // Commit the transaction

            // On success:
            JOptionPane.showMessageDialog(this, String.format(
                "BOOKING SUCCESS!\n\nQueue ID: %s\nSeats Reserved: %s\nTotal Price: ₱%.2f",
                currentQueueId != null ? currentQueueId : "N/A",
                seatList,
                finalPrice
            ), "Booking Confirmed", JOptionPane.INFORMATION_MESSAGE);

            // Reload seats to visually mark them as permanently reserved
            loadSeatStatus(); 

        } catch (SQLException ex) {
            try {
                conn.rollback(); // Roll back if any part of the transaction failed
            } catch (SQLException rollbackEx) {
                System.err.println("Rollback failed: " + rollbackEx.getMessage());
            }

            // Check for UNIQUE constraint violation (someone else booked during selection)
            if (ex.getErrorCode() == 19) {
                JOptionPane.showMessageDialog(this, "One or more of your selected seats were just taken. Please reload and try again.", "Booking Conflict", JOptionPane.ERROR_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(this, "Database error during booking: " + ex.getMessage(), "SQL Error", JOptionPane.ERROR_MESSAGE);
            }
            loadSeatStatus(); // Reload the map to reflect actual availability

        } finally {
            // Restore auto-commit and close statement
            try { if (pstmt != null) pstmt.close(); } catch (SQLException closingEx) { /* ignored */ }
            try { conn.setAutoCommit(true); } catch (SQLException restoringEx) { /* ignored */ }
        }
    }
}