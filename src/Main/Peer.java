package Main;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import Files.FileID;
import Files.FileSystem;
import Files.MyFile;
import InitiatorProtocol.FileBackup;
import InitiatorProtocol.FileDeletion;
import InitiatorProtocol.FileRestore;
import Listeners.ListenToMC;
import Listeners.ListenToMDB;
import Listeners.ListenToMDR;
import Message.Message;
import ProtocolManagers.BackupManager;
import ProtocolManagers.DeleteManager;
import ProtocolManagers.RestoreManager;
import ProtocolManagers.SpaceReclaimingManager;
import Utilities.Pair;
import Utilities.Triple;


public class Peer {
	/*ARGUMENTS -> MC_IP, MC_PORT, MDB_IP, MDB_PORT, MBD_IP, MDB_PORT*/
	
	public static Vector<Message> stored_messages;
	public static Vector<Message> putchunk_messages;
	public static Vector<Message> getchunk_messages;
	public static Vector<Message> chunk_messages;
	public static Vector<Message> delete_messages;
	public static Vector<Message> removed_messages;
	/*
	 * <IP, FILEID, CHUNKNO>
	 */
	public static Vector<Triple<String, String, Integer>> peers;
	/*
	 * <<FILEID, CHUNKNO, CHUNKRD>, ACTUALRD>
	 */
	public static Vector<Pair<Triple<String, Integer, Integer>, Integer>> chunks;
	
	public static Lock mutex_stored_messages;
	public static Lock mutex_chunk_messages;
	/*
	 * STRING -> PATH ; PAIR -> <FILEID, NOFCHUNKS>
	 */
	public static ConcurrentHashMap<String, Pair> fileList;
	
	private static Thread ltmcThread;
	private static Thread ltmdbThread;
	private static Thread ltmdrThread;
	private static Thread bumThread;
	private static Thread rmThread;
	private static Thread dmThread;
	private static Thread srmThread;
	
	public static void main(String args[]) throws IOException{
		/*if(args.length == 6){
			setUpSockets(args);
		}*/
		setUpSocketsDefault();
		
		System.out.println(InetAddress.getLocalHost());
		
		mutex_stored_messages = new ReentrantLock(true);
		mutex_chunk_messages = new ReentrantLock(true);
		
		stored_messages = new Vector<Message>();
		putchunk_messages = new Vector<Message>();
		getchunk_messages = new Vector<Message>();
		chunk_messages = new Vector<Message>();
		delete_messages = new Vector<Message>();
		removed_messages = new Vector<Message>();
		
		fileList = new ConcurrentHashMap<String, Pair>();
		
		peers = new Vector<Triple<String, String, Integer>>();
		chunks = new Vector<Pair<Triple<String, Integer, Integer>, Integer>>();
		
		
		ListenToMC ltmc = new ListenToMC();
		ltmcThread = new Thread(ltmc);
		ltmcThread.start();
		
		ListenToMDB ltmdb = new ListenToMDB();
		ltmdbThread = new Thread(ltmdb);
	
		ListenToMDR ltmdr = new ListenToMDR();
		ltmdrThread = new Thread(ltmdr);
		ltmdrThread.start();
		
		BackupManager bum = new BackupManager();
		bumThread = new Thread(bum);
		
		RestoreManager rm = new RestoreManager();
		Thread rmThread = new Thread(rm);
		
		DeleteManager dm = new DeleteManager();
		dmThread = new Thread(dm);
		
		SpaceReclaimingManager srm = new SpaceReclaimingManager();
		srmThread = new Thread(srm);
		
		while(true){
			BufferedReader inFromUser = new BufferedReader(new InputStreamReader(System.in));
			String command = inFromUser.readLine();
			String parts[] = command.split(" ");
			if(parts.length == 2 && parts[0].equals("BACKUP")){
				String filename = parts[1];
				MyFile file = new MyFile(filename);
				FileBackup fb = new FileBackup(file, 1);
				fb.Send();
			} else if(parts.length == 2 && parts[0].equals("RESTORE")){
				String filename = parts[1];
				FileRestore fr = new FileRestore(filename, "restoredFiles" + File.separator + filename);
			} else if(parts.length == 2 && parts[0].equals("DELETE")){
				String filename = parts[1];
				FileDeletion fd = new FileDeletion(filename);
			} else if(command.equals("QUIT")){
				quit();
			} else if(command.equals("PEER")){
				break;
			} else {
				System.out.println("INVALID INPUT");
			}
		}
		/*
		 * 
		 */
		System.out.println("ACTING AS PEER - JOINING GROUP...");
		ltmdbThread.start();
		bumThread.start();
		dmThread.start();
		rmThread.start();
		srmThread.start();
	}
	
	public static void writeChunk(Message msg){
		String path = null;
		if(msg.type == Message.Type.PUTCHUNK){
			path = backupPath + File.separator + msg.getHexFileID() + File.separator + msg.chunkNo.toString();
		} else {
			path = restorePath + File.separator + msg.getHexFileID() + File.separator + msg.chunkNo.toString();
		}
		long writtenSize = FileSystem.writeByteArray(path, msg.getBody());
	}
	
	public static int getStoredMessages(Chunk chunk){
		int count = 0;
		mutex_stored_messages.lock();
		for(Message m : stored_messages){
			if(m.getFileID().toString().equals(chunk.fileID._hexFileID)
					&& m.chunkNo == chunk.chunkNo){
				count++;
			}
		}
		mutex_stored_messages.unlock();
		return count;
	}
	
	public static void removeStoredMessages(Chunk chunk){
		for(int i = stored_messages.size()-1; i >=0; i--){
			Message m = stored_messages.elementAt(i);
			if(m.getFileID().toString().equals(chunk.fileID._hexFileID)
					&& m.chunkNo == chunk.chunkNo){
				stored_messages.remove(m);
			}
		}
	}
	
	public static Message chunkMessageExists(Message msg){
		for(Message m : chunk_messages){
			if(m.getFileID().toString().equals(msg.getFileID().toString()) && m.getChunkNo() == msg.getChunkNo()){
				return m;
			}
		}
		return null;
	}
	
	public static byte[] readChunk(FileID fileId, Integer chunkNo) throws IOException {
		//System.out.println(getBackupDir() + File.separator + fileId.toString() + File.separator + chunkNo.toString());
        File f = new File(getBackupDir() + File.separator + fileId.toString() + File.separator + chunkNo.toString());
        if (!f.exists()) 
            throw new FileNotFoundException();
        
        BufferedInputStream bis = new BufferedInputStream(new FileInputStream(f));
        byte[] chunk = new byte[(int)f.length()];
        
        bis.read(chunk);        
        
        bis.close();
        return chunk;
    }
	
	public static void removeOwnFile(String path){
		File file = new File(path);
		if(file.delete())
			System.out.println("DELETED " + path);
		else System.out.println("FAILED TO DELETE FILE " + path);
	}
	
	public static void updateActualRepDegree(Message message){
		for(int i = 0; i < chunks.size(); i++){
			Pair<Triple<String, Integer, Integer>, Integer> chunk = chunks.elementAt(i);
			
			String fileID = chunk.getfirst().getFirst();
			Integer chunkNo = chunk.getfirst().getSecond();
			Integer actualRD = chunk.getsecond();
			
			if(message.getFileID().toString().equals(fileID) && message.chunkNo == chunkNo){
				chunk.setsecond(actualRD + 1);
				System.out.println("UPDATED ARD OF " + fileID + " " + chunkNo + " ARD: " + chunk.getsecond());
			}
		}
	}
	
	public static void addChunk(Message message){
		Pair<Triple<String, Integer, Integer>, Integer> newChunk = new Pair<Triple<String, Integer, Integer>, Integer>
																	(new Triple<String, Integer, Integer>
																		(message.getFileID().toString(), message.chunkNo, message.getReplicationDeg()), 0);
		chunks.addElement(newChunk);
		System.out.println("ADDED A NEW CHUNK " + message.getFileID().toString() + " " + message.getChunkNo());
	}
	
	private static void quit(){
		if(ltmcThread != null)
			ltmcThread.interrupt();
		if(ltmdbThread != null)
			ltmdbThread.interrupt();
		if(ltmdrThread != null)
			ltmdrThread.interrupt();
		if(bumThread != null)
			bumThread.interrupt();
		if(rmThread != null)
			rmThread.interrupt();
		if(dmThread != null)
			dmThread.interrupt();
		mc_socket.close();
		mdb_socket.close();	
		mdr_socket.close();
		System.exit(0);
	}
	
	static void setUpSockets(String args[]) throws IOException{
		/*MULTICAST CONTROL SETUP*/
		mc_saddr = new InetSocketAddress(args[0], Integer.parseInt(args[1]));
		mc_port = mc_saddr.getPort();
		mc_socket = new MulticastSocket(mc_saddr.getPort());
		mc_socket.setTimeToLive(1);
		/*MULTICAST DATA BACKUP CONTROL*/
		mdb_saddr = new InetSocketAddress(args[2], Integer.parseInt(args[3]));
		mdb_port = mdb_saddr.getPort();
		mdb_socket = new MulticastSocket(mdb_saddr.getPort());
		mdb_socket.setTimeToLive(1);
		/*MULTICAST DATA RESTORE CONTROL*/
		mdr_saddr = new InetSocketAddress(args[4], Integer.parseInt(args[5]));
		mdr_port = mdr_saddr.getPort();
		mdr_socket = new MulticastSocket(mdr_saddr.getPort());
		mdr_socket.setTimeToLive(1);	
	}
	
	static void setUpSocketsDefault() throws IOException{
		/*MULTICAST CONTROL SETUP*/
		mc_saddr = new InetSocketAddress("239.0.0.4", 4444);
		mc_port = mc_saddr.getPort();
		mc_socket = new MulticastSocket(mc_saddr.getPort());
		mc_socket.setTimeToLive(1);
		/*MULTICAST DATA BACKUP CONTROL*/
		mdb_saddr = new InetSocketAddress("239.0.0.3", 3333);
		mdb_port = mdb_saddr.getPort();
		mdb_socket = new MulticastSocket(mdb_saddr.getPort());
		mdb_socket.setTimeToLive(1);
		/*MULTICAST DATA RESTORE CONTROL*/
		mdr_saddr = new InetSocketAddress("239.0.0.5", 5555);
		mdr_port = mdr_saddr.getPort();
		mdr_socket = new MulticastSocket(mdr_saddr.getPort());
		mdr_socket.setTimeToLive(1);
	}
	
	public static String getBackupDir(){return backupPath;}
	public static String getRestoreDir(){return restorePath;}
	
	/*MULTICAST CONTROL SOCKET*/
	public static MulticastSocket mc_socket;
	public static int mc_port;
	public static String mc_addr;
	public static InetSocketAddress mc_saddr;
	/*MULTICAST DATA BACKUP SOCKET*/
	public static MulticastSocket mdb_socket;
	public static int mdb_port;
	public static String mdb_addr;
	public static InetSocketAddress mdb_saddr;
	/*MULTICAST DATA RESTORE SOCKET*/
	public static MulticastSocket mdr_socket;
	public static int mdr_port;
	public static String mdr_addr;
	public static InetSocketAddress mdr_saddr;
	
	private final static String backupPath = "backup";
	private final static String restorePath = "restore";
	
}