package Project.server;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

import Project.common.Constants;

public class Room implements AutoCloseable {
    // server is a singleton now so we don't need this
    // protected static Server server;// used to refer to accessible server
    // functions
    private String name;
    private List<ServerThread> clients = new ArrayList<ServerThread>();
    private boolean isRunning = false;
    // Commands
    private final static String COMMAND_TRIGGER = "/";
    private final static String CREATE_ROOM = "createroom";
    private final static String JOIN_ROOM = "joinroom";
    private final static String DISCONNECT = "disconnect";
    private final static String LOGOUT = "logout";
    private final static String LOGOFF = "logoff";
    private final static String MUTE ="mute";
    private final static String UNMUTE ="unmute";
    private static Logger logger = Logger.getLogger(Room.class.getName());

    public Room(String name) {
        this.name = name;
        isRunning = true;
    }

    public String getName() {
        return name;
    }

    public boolean isRunning() {
        return isRunning;
    }

    protected synchronized void addClient(ServerThread client) {
        if (!isRunning) {
            return;
        }
        client.setCurrentRoom(this);
        if (clients.indexOf(client) > -1) {
            logger.warning("Attempting to add client that already exists in room");
        } else {
            clients.add(client);
            client.sendResetUserList();
            syncCurrentUsers(client);
            sendConnectionStatus(client, true);
        }
    }

    protected synchronized void removeClient(ServerThread client) {
        if (!isRunning) {
            return;
        }
        // attempt to remove client from room
        try {
            clients.remove(client);
        } catch (Exception e) {
            logger.severe(String.format("Error removing client from room %s", e.getMessage()));
            e.printStackTrace();
        }
        // if there are still clients tell them this person left
        if (clients.size() > 0) {
            sendConnectionStatus(client, false);
        }
        checkClients();
    }

    private void syncCurrentUsers(ServerThread client) {
        Iterator<ServerThread> iter = clients.iterator();
        while (iter.hasNext()) {
            ServerThread existingClient = iter.next();
            if (existingClient.getClientId() == client.getClientId()) {
                continue;// don't sync ourselves
            }
            boolean messageSent = client.sendExistingClient(existingClient.getClientId(),
                    existingClient.getClientName());
            if (!messageSent) {
                handleDisconnect(iter, existingClient);
                break;// since it's only 1 client receiving all the data, break if any 1 send fails
            }
        }
    }

    /***
     * Checks the number of clients.
     * If zero, begins the cleanup process to dispose of the room
     */
    private void checkClients() {
        // Cleanup if room is empty and not lobby
        if (!name.equalsIgnoreCase(Constants.LOBBY) && (clients == null || clients.size() == 0)) {
            close();
        }
    }
    //Brl23-11/20/23
    private String flip() {
        Random random = new Random();
        return random.nextBoolean() ? "heads" : "tails";
    }

    private String roll(int numDice, int numSides) {
        Random random = new Random();
        int total = 0;
        for (int i = 0; i < numDice; i++) {
            total += random.nextInt(numSides) + 1;
        }
        return Integer.toString(total);
    }

    /***
     * Helper function to process messages to trigger different functionality.
     * 
     * @param message The original message being sent
     * @param client  The sender of the message (since they'll be the ones
     *                triggering the actions)
     */
    @Deprecated // not used in my project as of this lesson, keeping it here in case things
                // change
    private boolean processCommands(String message, ServerThread client) {

        boolean wasCommand = false;
        try {
            if (message.startsWith(COMMAND_TRIGGER)) {
                String[] comm = message.split(COMMAND_TRIGGER);
                String part1 = comm[1];
                String[] comm2 = part1.split(" ");
                String command = comm2[0];
                String roomName;
                wasCommand = true;
                switch (command) {
                    case CREATE_ROOM:
                        roomName = comm2[1];
                        Room.createRoom(roomName, client);
                        break;
                    case JOIN_ROOM:
                        roomName = comm2[1];
                        Room.joinRoom(roomName, client);
                        break;
                    case DISCONNECT:
                    case LOGOUT:
                    case LOGOFF:
                        Room.disconnectClient(client, this);
                        break;
                    //Brl23-11/20/23
                    case "flip":
                    case "toss":
                    case "coin":
                        String result = flip();
                        sendMessage(client, (String.format("_flipped a coin and got %s_", result)));
                        break;
                    //Brl23-11/20/23
                    case "roll":
                        String roll = comm2[1];
                        
                        if (roll.contains("d")) {
                        
                        String[] diceTokens = roll.split("d");
                        
                        int numDice = Integer.parseInt(diceTokens[0]);
                        int numSides = Integer.parseInt(diceTokens[1]);
                        String r = roll(numDice, numSides);
                        sendMessage(client, (String.format("_rolled %s and got %s_", comm2[1], r)));
                    } else {
                        int numSides= Integer.parseInt(roll);
                        String r = roll(1, numSides);
                        sendMessage(client, (String.format("_rolled %s and got %s_", comm2[1], r)));
                    }
                        break;
                    //Brl23-12/11/23
                    case MUTE:
                        client.muteUser(comm2[1]);
                        break;
                    case UNMUTE:
                        client.unmuteUser(comm2[1]);
                        break;
            }

            //Private message
        } else if (message.startsWith("@")) {
            // Private message handling
            int spaceIndex = message.indexOf(" ");
            if (spaceIndex != -1) {
                String username = message.substring(1, spaceIndex);
                String privateMessage = message.substring(spaceIndex + 1);
                sendPrivateMessage(client, username, privateMessage);
                wasCommand = true;
            }
        }
    } catch (Exception e) {
        e.printStackTrace();
    }
    return wasCommand;
}
//Brl23-12/13/23
private void sendPrivateMessage(ServerThread sender, String username, String message) {
    for (ServerThread recipient : clients) {
        if (recipient.getClientName().equals(username)) {
            
            //boolean isSenderMuted = sender.isUserMuted(recipient.getClientName());
            boolean isRecipientMuted = recipient.isUserMuted(sender.getClientName());
            
            if (!isRecipientMuted) {
                recipient.sendMessage(sender.getClientId(), String.format("@%s: %s", sender.getClientName(), message));
                sender.sendMessage(recipient.getClientId(), String.format("Sent a private message to @%s: %s", username, message));
            } else {
                System.out.println("Private message not sent due to muting. Sender ID: " + sender.getClientName() + ", Recipient ID: " + recipient.getClientName());
            }
            
            return;
        }
    }


    // If the loop completes and no recipient is found, notify the sender
    sender.sendMessage(sender.getClientId(),String.format("User @%s not found or not online.", username));
}

    // Command helper methods
    protected static void getRooms(String query, ServerThread client) {
        String[] rooms = Server.INSTANCE.getRooms(query).toArray(new String[0]);
        client.sendRoomsList(rooms,
                (rooms != null && rooms.length == 0) ? "No rooms found containing your query string" : null);
    }

    protected static void createRoom(String roomName, ServerThread client) {
        if (Server.INSTANCE.createNewRoom(roomName)) {
            Room.joinRoom(roomName, client);
        } else {
            client.sendMessage(Constants.DEFAULT_CLIENT_ID, String.format("Room %s already exists", roomName));
        }
    }

    /**
     * Will cause the client to leave the current room and be moved to the new room
     * if applicable
     * 
     * @param roomName
     * @param client
     */
    protected static void joinRoom(String roomName, ServerThread client) {
        if (!Server.INSTANCE.joinRoom(roomName, client)) {
            client.sendMessage(Constants.DEFAULT_CLIENT_ID, String.format("Room %s doesn't exist", roomName));
        }
    }

    protected static void disconnectClient(ServerThread client, Room room) {
        client.disconnect();
        room.removeClient(client);
    }
    // end command helper methods
    //Brl23-11/27/23
    private String formattedMessage(String message){
    // Wrap text in * for bold
    message = message.replaceAll("\\*(.*?)\\*", "<b>$1</b>");

    // Wrap text in _ for italics
    message = message.replaceAll("_(.*?)_", "<i>$1</i>");

    // Wrap text in ~red()~ for red color
    message = message.replaceAll("~red\\(([^)]+)\\)~", "<span style=\"color: red;\">$1</span>");
    message = message.replaceAll("~green\\(([^)]+)\\)~", "<span style=\"color: green;\">$1</span>");
    message = message.replaceAll("~blue\\(([^)]+)\\)~", "<span style=\"color: blue;\">$1</span>");

    // Wrap text in ` for underline
    message = message.replaceAll("`([^`]+)`", "<u>$1</u>");
        return message;
    }
    /***
     * Takes a sender and a message and broadcasts the message to all clients in
     * this room. Client is mostly passed for command purposes but we can also use
     * it to extract other client info.
     * 
     * @param sender  The client sending the message
     * @param message The message to broadcast inside the room
     */
    //Brl23-12/13/23
    protected synchronized void sendMessage(ServerThread sender, String message) {
        if (!isRunning) {
            return;
        }
        logger.info(String.format("Sending message to %s clients", clients.size()));
        if (sender != null && processCommands(message, sender)) {
            // it was a command, don't broadcast
            return;
        }
        message=formattedMessage(message);
        // todo add formatting (i.e.,) message = formattedMessage(message)
        long from = sender == null ? Constants.DEFAULT_CLIENT_ID : sender.getClientId();
        Iterator<ServerThread> iter = clients.iterator();
        while (iter.hasNext()) {
        ServerThread client = iter.next();
        // Check if the target client is muted
        String targetClientID = client.getClientName();
        boolean isTargetMuted = client.isUserMuted(sender.getClientName());
        if (!isTargetMuted) {
             boolean messageSent = client.sendMessage(from, message);
             if (!messageSent) {
                handleDisconnect(iter, client);
                continue;
            }
            } else {
                System.out.println("Message not sent to muted user with ID: " + targetClientID);

            }
        }
    }

    protected synchronized void sendConnectionStatus(ServerThread sender, boolean isConnected) {
        Iterator<ServerThread> iter = clients.iterator();
        while (iter.hasNext()) {
            ServerThread receivingClient = iter.next();
            boolean messageSent = receivingClient.sendConnectionStatus(
                    sender.getClientId(),
                    sender.getClientName(),
                    isConnected);
            if (!messageSent) {
                handleDisconnect(iter, receivingClient);
            }
        }
    }

    private void handleDisconnect(Iterator<ServerThread> iter, ServerThread client) {
        iter.remove();
        logger.info(String.format("Removed client %s", client.getClientName()));
        sendMessage(null, client.getClientName() + " disconnected");
        checkClients();
    }

    public void close() {
        Server.INSTANCE.removeRoom(this);
        isRunning = false;
        clients.clear();
    }
}