package Project.server;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import Project.common.Constants;
import Project.common.Payload;
import Project.common.PayloadType;
import Project.common.RoomResultPayload;

/**
 * A server-side representation of a single client
 */
public class ServerThread extends Thread {
    public static final String isuserMuted = null;
    private Socket client;
    private String clientName;
    private boolean isRunning = false;
    private ObjectOutputStream out;// exposed here for send()
    // private Server server;// ref to our server so we can call methods on it
    // more easily
    private Room currentRoom;
    private static Logger logger = Logger.getLogger(ServerThread.class.getName());
    private long myClientId;
    //Brl23-12/11/23
    private Set<String> mutedUsers = new HashSet<>();

    public void setClientId(long id) {
        myClientId = id;
    }

    public long getClientId() {
        return myClientId;
    }

    public boolean isRunning() {
        return isRunning;
    }

    public ServerThread(Socket myClient, Room room) {
        logger.info("ServerThread created");
        // get communication channels to single client
        this.client = myClient;
        this.currentRoom = room;

    }

    protected void setClientName(String name) {
        if (name == null || name.isBlank()) {
            logger.warning("Invalid name being set");
            return;
        }
        clientName = name;
    }

    public String getClientName() {
        return clientName;
    }

    protected synchronized Room getCurrentRoom() {
        return currentRoom;
    }

    protected synchronized void setCurrentRoom(Room room) {
        if (room != null) {
            currentRoom = room;
        } else {
            logger.info("Passed in room was null, this shouldn't happen");
        }
    }

    public void disconnect() {
        sendConnectionStatus(myClientId, getClientName(), false);
        logger.info("Thread being disconnected by server");
        isRunning = false;
        cleanup();
    }

    // send methods

    public boolean sendReadyStatus(long clientId) {
        Payload p = new Payload();
        p.setPayloadType(PayloadType.READY);
        p.setClientId(clientId);
        return send(p);
    }

    public boolean sendRoomName(String name) {
        Payload p = new Payload();
        p.setPayloadType(PayloadType.JOIN_ROOM);
        p.setMessage(name);
        return send(p);
    }

    public boolean sendRoomsList(String[] rooms, String message) {
        RoomResultPayload payload = new RoomResultPayload();
        payload.setRooms(rooms);
        if (message != null) {
            payload.setMessage(message);
        }
        return send(payload);
    }

    public boolean sendExistingClient(long clientId, String clientName) {
        Payload p = new Payload();
        p.setPayloadType(PayloadType.SYNC_CLIENT);
        p.setClientId(clientId);
        p.setClientName(clientName);
        return send(p);
    }

    public boolean sendResetUserList() {
        Payload p = new Payload();
        p.setPayloadType(PayloadType.RESET_USER_LIST);
        return send(p);
    }

    public boolean sendClientId(long id) {
        Payload p = new Payload();
        p.setPayloadType(PayloadType.CLIENT_ID);
        p.setClientId(id);
        return send(p);
    }

    public boolean sendMessage(long clientId, String message) {
        Payload p = new Payload();
        p.setPayloadType(PayloadType.MESSAGE);
        p.setClientId(clientId);
        p.setMessage(message);
        return send(p);
    }

    public boolean sendConnectionStatus(long clientId, String who, boolean isConnected) {
        Payload p = new Payload();
        p.setPayloadType(isConnected ? PayloadType.CONNECT : PayloadType.DISCONNECT);
        p.setClientId(clientId);
        p.setClientName(who);
        p.setMessage(String.format("%s the room %s", (isConnected ? "Joined" : "Left"), currentRoom.getName()));
        return send(p);
    }

    private boolean send(Payload payload) {
        try {
            logger.log(Level.FINE, "Outgoing payload: " + payload);
            out.writeObject(payload);
            logger.log(Level.INFO, "Sent payload: " + payload);
            return true;
        } catch (IOException e) {
            logger.info("Error sending message to client (most likely disconnected)");
            // uncomment this to inspect the stack trace
            // e.printStackTrace();
            cleanup();
            return false;
        } catch (NullPointerException ne) {
            logger.info("Message was attempted to be sent before outbound stream was opened: " + payload);
            // uncomment this to inspect the stack trace
            // e.printStackTrace();
            return true;// true since it's likely pending being opened
        }
    }

    // end send methods

    void processPayload(Payload p) {
        switch (p.getPayloadType()) {
            case CONNECT:
                setClientName(p.getClientName());
                break;
            case DISCONNECT:
                Room.disconnectClient(this, getCurrentRoom());
                break;
            case MESSAGE:
                if (currentRoom != null) {
                    currentRoom.sendMessage(this, p.getMessage());
                } else {
                    // TODO migrate to lobby
                    logger.log(Level.INFO, "Migrating to lobby on message with null room");
                    Room.joinRoom(Constants.LOBBY, this);
                }
                break;
            case GET_ROOMS:
                Room.getRooms(p.getMessage().trim(), this);
                break;
            case CREATE_ROOM:
                Room.createRoom(p.getMessage().trim(), this);
                break;
            case JOIN_ROOM:
                Room.joinRoom(p.getMessage().trim(), this);
                break;
            case READY:
                // ((GameRoom) currentRoom).setReady(myClientId);
                break;
            default:
                break;

        }

    }
    //Muting/Unmuting
    public boolean muteUser(String name) {
        if (mutedUsers.contains(name)) {
            return false; // Already muted
        }

        mutedUsers.add(name);
        return true;
    }

    // Unmuting
    public boolean unmuteUser(String name) {
        if (!mutedUsers.contains(name)) {
            return false; // Not muted
        }

        mutedUsers.remove(name);
        return true;
    }

    // Check if a user is muted
    public boolean isUserMuted(String targetClientID){
        return mutedUsers.contains(targetClientID);
    }
    // end send methods
    @Override
    public void run() {
        try (ObjectOutputStream out = new ObjectOutputStream(client.getOutputStream());
                ObjectInputStream in = new ObjectInputStream(client.getInputStream());) {
            this.out = out;
            isRunning = true;
            Payload fromClient;
            while (isRunning && (fromClient = (Payload) in.readObject()) != null) {
                logger.info("Received from client: " + fromClient);
                if (!isUserMuted(fromClient.getClientName())) {
                    processPayload(fromClient);
                } else {
                    sendMutedUserMessage(fromClient.getClientId());
                }
            }
        } catch (Exception e) {
            // Handle exceptions
            e.printStackTrace();
        } finally {
            isRunning = false;
            logger.info("Exited thread loop. Cleaning up connection");
            cleanup();
        }
    }
    private void sendMutedUserMessage(long mutedUserId) {
        String message = "You are muted and cannot perform this action.";
        Payload mutedUserMessagePayload = new Payload();
        mutedUserMessagePayload.setPayloadType(PayloadType.MUTED_USER);
        mutedUserMessagePayload.setMessage(message);
        sendMessage(mutedUserId, mutedUserMessagePayload);
    }

    private void sendMessage(long mutedUserId, Payload mutedUserMessagePayload) {
    }

    private void cleanup() {
        logger.info("Thread cleanup() start");
        try {
            client.close();
        } catch (IOException e) {
            logger.info("Client already closed");
        }
        logger.info("Thread cleanup() complete");
    }
}