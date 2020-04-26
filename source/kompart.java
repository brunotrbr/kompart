import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public class kompart {
	static final int MAX_BUFFER_SIZE = 1024;
	static final int DEFAULT_PORT = 4500;

	static Thread _threadHeartbeat;
	static Thread _threadHeartbeatServer;
	static Thread _threadReceiveMessage;
	static int _defaultHeartBeat = 2;
	static ArrayList<KFile> _registeredFiles  = new ArrayList<>();
	static ArrayList<HashMap<InetAddress,KFile>> _requestedFiles = new ArrayList<>();
	static HashMap<InetAddress,Peer> peers = new HashMap<InetAddress,Peer>();
	static ArrayList<InetAddress> peersToRemove = new ArrayList<>();

	public static void main(String[] args) throws IOException, NoSuchAlgorithmException {
		if (args.length != 2) {
			System.out.println("Usage: java kompart <server IP> <type>");
			System.out.println("Available types: s (run as server) and p (run as peer)");
			return;
		}

		if (!args[1].toLowerCase().equals("s") && !args[1].toLowerCase().equals("p")) {
			System.out.println("Unrecognized type: " + args[1]);
			System.out.println("\nUsage: java kompart <server IP> <type>");
			System.out.println("Available types: s (run as server) and p (run as peer)");
			return;
		}

		if (args[1].toLowerCase().equals("s"))
			RunServer();
		else
			RunPeer(args[0]);

	}

	public static void RunServer() throws IOException {
		String received = null;
		String message = null;
		Boolean hasSend = false;

		InetAddress address;
		int port;
		byte[] text = new byte[MAX_BUFFER_SIZE];
		
		DatagramSocket socket = new DatagramSocket(DEFAULT_PORT);
		DatagramPacket packet;

		_threadHeartbeatServer = _threadHeartbeat = StartHeartbeatServer();
		_threadHeartbeatServer.start();

		while (true) {
			try {
				hasSend = false;
				text = new byte[MAX_BUFFER_SIZE];
				
				// receive datagram
				packet = new DatagramPacket(text, text.length);
				socket.setSoTimeout(500);
				socket.receive(packet);

				// process received datagram
				received = new String(packet.getData(), 0, packet.getLength());
				address = packet.getAddress();
				port = packet.getPort();

				// store text as array
				String[] splittedReceived = received.split(" ");
				
				if(splittedReceived.length == 1){
					if (splittedReceived[0].equalsIgnoreCase("exit")) {
						// set message to peer
						message = "exit";
						if (peers.containsKey(address)) {
							peers.remove(address);
						}
						// send response to peer
						SendPacketServer(socket, packet, text, address, port, message);
						hasSend = true;
					} else if (splittedReceived[0].equalsIgnoreCase("break")) {
						// set message to peer
						message = "exit";
	
						// send response to peer
						SendPacketServer(socket, packet, text, address, port, message);
						hasSend = true;
						break;
					} else if (splittedReceived[0].equalsIgnoreCase("heartbeat")) {
						address = packet.getAddress();
	
						if(peers.containsKey(address)){
							int heartbeat = peers.get(address).GetHeartbeat();
							peers.get(address).SetHeartbeat(heartbeat + 1);
						}
						hasSend = true;
					} else if (splittedReceived[0].equalsIgnoreCase("disconnect")) {
						if (peers.containsKey(address)) {
							peers.remove(address);
							// set message to peer
							message = "Peer " + address + " removed.\n";
						} else {
							message = "Peer " + address + " is not registered.\n";
						}
	
						// send response to peer
						SendPacketServer(socket, packet, text, address, port, message);
						hasSend = true;
					}
				} else if(splittedReceived.length >= 2){
					if (splittedReceived[0].equalsIgnoreCase("register")) {
						if (splittedReceived[1].equals("peer")) {
							// Check if peer has registered before
							if (peers.containsKey(address)) {
								message = "Peer " + address + " already registered.\n";
							} else {
								// add IP to map of peers
								peers.put(address, new Peer(address, port, _defaultHeartBeat));
								message = "Peer " + address + " registered.\n";
							}
							// send response to peer
							SendPacketServer(socket, packet, text, address, port, message);
							hasSend = true;
						} else if (splittedReceived[1].equals("file")) {
							// Check if peer has registered before
							if (peers.containsKey(address)) {
								// Check if file has registered before
								if (peers.get(address).GetFiles().containsKey(splittedReceived[2])) {
									message = "File '" + splittedReceived[2] + "' already registered.\n";
								} else {
									KFile kf = new KFile(splittedReceived[2], splittedReceived[3]);
									peers.get(address).GetFiles().put(splittedReceived[2], kf);
									message = "File '" + splittedReceived[2] + "' registered.\n";
								}
							} else {
								message = "Peer " + address + " is not registered.\n";
							}
							// send response to peer
							SendPacketServer(socket, packet, text, address, port, message);
							hasSend = true;
						}
					} else if (splittedReceived[0].equalsIgnoreCase("list")
							&& splittedReceived[1].equalsIgnoreCase("files")) {
						if(peers.containsKey(address)){
							Boolean hasFiles = false;
							message = "Available files: \n";
		
							for (Map.Entry<InetAddress, Peer> peer : peers.entrySet()) {
								if (peer.getKey() != address) {
									message += peer.getValue().GetAddress() + "\n";
									for (Map.Entry<String, KFile> file : peer.getValue().GetFiles().entrySet()) {
										message += "  - filename: " + file.getValue().GetFileName() + " , hash: "
												+ file.getValue().GetHash() + "\n";
										hasFiles = true;
									}
								}
							}
		
							if (!hasFiles) {
								message = "No registered files.\n";
							}
						} else {
							message = "Peer " + address + " is not registered.\n";
						}
						
						// send response to peer
						SendPacketServer(socket, packet, text, address, port, message);
						hasSend = true;
					}
				}

				if(!hasSend){
					// unrecognized command
					message = "Unrecognized command.\n";
					// send response to peer
					SendPacketServer(socket, packet, text, address, port, message);
				}
			} catch (IOException e) {
				
			}
		}

		socket.close();
		_threadHeartbeatServer.interrupt();
		System.out.println("Server closed...\n");
	}

	public static void RunPeer(String serverIp) throws IOException, NoSuchAlgorithmException {
		String input = "";
		Scanner in = new Scanner(System.in);
		byte[] text = new byte[MAX_BUFFER_SIZE];

		// create socket datagram
		DatagramSocket socket = new DatagramSocket();
		DatagramPacket packet = null;
		InetAddress address = InetAddress.getByName(serverIp);

		System.out.println("Welcome to Kompart P2P.\nType 'register peer' to be registered.\n");
		do{
			input = in.nextLine();
			if(!input.equals("register peer")){
				System.out.println("Type 'register peer' to be registered.\n");
			}
		} while(!input.equals("register peer"));

		// send packet to server
		text = input.getBytes();
		SendPacketClient(socket, packet, text, address, DEFAULT_PORT, input);

		// Start heartbeat thread
		_threadHeartbeat = StartHeartbeatClient(address, socket);
		_threadHeartbeat.start();

		// Start receive messages thread
		_threadReceiveMessage = new ReceiveMessagesThread(socket);
		_threadReceiveMessage.start();

		while (true) {
			try {
				if(input.equalsIgnoreCase("break") || input.equalsIgnoreCase("exit")){
					// close socket
					socket.close();
					// close scanner
					in.close();
					break;
				}

				Thread.sleep(1000);
				ShowCommands();
				input = in.nextLine();
				System.out.println("\n");

				if(input.toLowerCase().contains("register file")){
					// split file name and path
					String fullfile = input.split(" ")[2];
					String name = fullfile.split("/")[1];
					String path = fullfile.split("/")[0];

					// calculate hash and append to command
					byte[] hashByte = GenerateHash(fullfile);
					String hash = ConvertHashToString(hashByte);
					input = "register file " + name + " " + hash;

					// add to registered files
					KFile kfile = new KFile(name, path, hash);
					_registeredFiles.add(kfile);

					SendPacketClient(socket, packet, text, address, DEFAULT_PORT, input);
				} else if(input.toLowerCase().contains("get files")){ 
					String[] arrayInput = input.split(" ");
					String[] files = new String[arrayInput.length -2];
					for(int i = 0 ; i < arrayInput.length ; i++){
						if(!arrayInput[i].equals("get") && !arrayInput[i].equals("files"))
							files[i-2] = arrayInput[i];
					}
					// nesse ponto, "files" tem uma lista de IP/nome ou IP/hash
					// percorrer o FILES, e enviar o pacote para cada address que tiver na lista, um arquivo por vez
					
					for(int i = 0 ; i < files.length ; i++){
						System.out.println("Requesting file[" + i + "] = " + files[i]);
					}
					System.out.println("\n");
				} else if(input.toLowerCase().contains("get all")){ 

				} else { // just send packet
					SendPacketClient(socket, packet, text, address, DEFAULT_PORT, input);
				}
			} catch (InterruptedException ie) {

			}
		}

		_threadHeartbeat.interrupt();
		_threadReceiveMessage.interrupt();
		System.out.println("\nProgram closed.\n");
		System.exit(0);
	}

	public static class ReceiveMessagesThread extends Thread {
		protected DatagramSocket socket = null;

		public ReceiveMessagesThread(DatagramSocket ds) throws IOException {
			socket = ds;
		}

		public void run() {
			byte[] text = new byte[MAX_BUFFER_SIZE];
			while (true) {
				try {
					// Thread.sleep(1000);
					text = new byte[MAX_BUFFER_SIZE];

					// get response
					DatagramPacket packet = new DatagramPacket(text, text.length);
					socket.setSoTimeout(500);
					socket.receive(packet);
					String response = new String(packet.getData(), 0, packet.getLength());
					
					if (response.toLowerCase().equals("exit")){
						System.out.println("[server] " + response);
						break;
					} else if(response.toLowerCase().contains("get file")){
						System.out.println("Received " + response);

						System.out.println("Sending " + response);
					} else if(response.toLowerCase().contains("get all")){
						System.out.println("Received " + response);

						System.out.println("Sending " + response);
					} else {
						System.out.println("[server] " + response);
					}
				} catch (IOException e) {

				}
			}
			socket.close();
		}
	}

	// Threads Heartbeat
	public static Thread StartHeartbeatServer() {
		return new Thread() {

			@Override
			public void run() {
				try {
					while (true) {
						for (Map.Entry<InetAddress, Peer> pair : peers.entrySet()) {
							// decrement hearbeat;
							int hearbeat = pair.getValue().GetHeartbeat();
							pair.getValue().SetHeartbeat(hearbeat - 1);
	
							// mark peers to remove if heartbeat less than 0
							if (pair.getValue().GetHeartbeat() <= 0) {
								peersToRemove.add(pair.getKey());
							}
						}

						// remove peers
						for (InetAddress inetAddress : peersToRemove) {
							System.out.println("removing: " + inetAddress);
							peers.remove(inetAddress);
						}
	
						// clean peer's list to remove
						peersToRemove.clear();
	
						// // print registered users
						// System.out.println("Registered users:");
						// for (Map.Entry<InetAddress, Peer> pair : peers.entrySet()) {
						// 	System.out.println(pair.getValue().GetAddress() + " hearbeat: " + pair.getValue().GetHeartbeat());
						// }
						Thread.sleep(5000);
					}
				} catch (InterruptedException e) {

				}
			}
		};
	}
	
	public static Thread StartHeartbeatClient(InetAddress address, DatagramSocket ds) throws IOException {
		return new Thread() {

			@Override
			public void run() {
				try {
					String input = "";

					byte[] text = new byte[MAX_BUFFER_SIZE];
					DatagramSocket socket = ds;
					DatagramPacket packet;

					while (true) {
						input = "heartbeat";
						text = input.getBytes();
						packet = new DatagramPacket(text, text.length, address, DEFAULT_PORT);
						socket.send(packet);
						Thread.sleep(5000);
					}
				} catch (SocketException se) {
					se.printStackTrace();
				} catch (IOException ioe) {
					ioe.printStackTrace();
				} catch (InterruptedException e) {

				}
			}
		};
	}

	// Auxiliar Classes
	public static class Peer {
		private InetAddress addr;
		private int port;
		private int heartbeat;
		private HashMap<String, KFile> files;

		public Peer(InetAddress address, int port, int heartbeat) {
			SetAddress(address);
			SetPort(port);
			SetHeartbeat(heartbeat);
			files = new HashMap<>();
		}

		public InetAddress GetAddress() {
			return this.addr;
		}

		public int GetPort() {
			return this.port;
		}

		public int GetHeartbeat() {
			return this.heartbeat;
		}

		public HashMap<String, KFile> GetFiles() {
			return this.files;
		}

		public void SetAddress(InetAddress address) {
			this.addr = address;
		}

		public void SetPort(int port) {
			this.port = port;
		}

		public void SetHeartbeat(int heartbeat) {
			this.heartbeat = heartbeat;
		}
	}

	public static class KFile {
		private String name;
		private String path;
		private String hash;

		public KFile(String name, String path, String hash) {
			SetFileName(name);
			SetFilePath(path);
			SetHash(hash);
		}

		public KFile(String name, String hash) {
			SetFileName(name);
			SetHash(hash);
		}

		public String GetFileName() {
			return this.name;
		}
		
		public String GetFilePath() {
			return this.path;
		}

		public String GetHash() {
			return this.hash;
		}

		public void SetFileName(String name) {
			this.name = name;
		}

		public void SetFilePath(String path) {
			this.path = path;
		}

		public void SetHash(String hash) {
			this.hash = hash;
		}
	}

	// Auxiliar Methods
	public static void ShowCommands() {
		System.out.println("Available options:");
		System.out.println("   register peer");
		System.out.println("   register file <path/filename>");
		System.out.println("   list files");
		System.out.println("   get files[] IP/filename ou IP/hash [IP/filename ou IP/hash...]");
		System.out.println("   get all <IP>");
		System.out.println("   disconnect");
		System.out.println("   exit\n");
	}

	private static byte[] GenerateHash(String pathFile) {
		byte[] hashByte = null;
		try {
			byte[] b = Files.readAllBytes(Paths.get(pathFile));
			MessageDigest md = MessageDigest.getInstance("MD5");
			md.update(b);
			hashByte = md.digest();
			return hashByte;
		} catch(IOException ioe){
			System.out.println("IO Exception");
			System.out.println(ioe.getMessage());
		} catch(NoSuchAlgorithmException e){
			System.out.println("No Such Algorithm Exception");
			System.out.println(e.getMessage());
		}
		return hashByte;
	}

	private static String ConvertHashToString(byte[] bytes) {
		if(bytes == null)
			return "";

		StringBuilder s = new StringBuilder();
		for (int i = 0; i < bytes.length; i++) {
			int parteAlta = ((bytes[i] >> 4) & 0xf) << 4;
			int parteBaixa = bytes[i] & 0xf;
			if (parteAlta == 0) s.append('0');
			s.append(Integer.toHexString(parteAlta | parteBaixa));
		}
		return s.toString();
	}

	private static void SendPacketServer(DatagramSocket socket, DatagramPacket packet, byte[] text, InetAddress address, int port, String message){
		try{
			text = message.getBytes();
			packet = new DatagramPacket(text, text.length, address, port);
			socket.send(packet);
			System.out.println("Sending package: " + message);
		} catch (IOException  e){
			System.out.println(e.getMessage());
		}
	}

	private static void SendPacketClient(DatagramSocket socket, DatagramPacket packet, byte[] text, InetAddress address, int port, String input){
		try{
			text = input.getBytes();
			packet = new DatagramPacket(text, text.length, address, port);
			socket.send(packet);
		} catch (IOException  e){
			System.out.println(e.getMessage());
		}
	}
}
