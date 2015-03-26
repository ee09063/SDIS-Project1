package InitiatorProtocol;
import java.io.IOException;
import java.net.DatagramPacket;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

import Files.FileSystem;
import Main.Chunk;
import Main.Peer;
import Message.Message;


public class ChunkBackup {
	private int count = 0;
	private int timeInterval = 500;
	final Message msg;
	final DatagramPacket msgPacket;
	
	public ChunkBackup(Chunk chunk) throws IOException{
		msg = Message.makePutChunk(chunk);
		/*
		 * 
		 */
		Peer.addChunk(msg);
		/*
		 * 
		 */
		byte[] temp = msg.toByteArray();
		System.out.println(temp.length);
		msgPacket = new DatagramPacket(temp,
										  temp.length,
										  Peer.mdb_saddr.getAddress(),
										  Peer.mdb_saddr.getPort());
		System.out.println("CREATING A PACKET SIZE IS " + msgPacket.getLength());
		SendDelay sd = new SendDelay();
		sd.startTask(msgPacket, chunk, msg);
	}
	
	public class SendDelay{
		private Timer timer = new Timer();
		DatagramPacket p;
		Message message;
		Chunk chunk;
		
		public void startTask(DatagramPacket p, Chunk chunk, Message msg){
			this.p = p;
			this.chunk = chunk;
			this.message = msg;
			Random rand = new Random();
			timer.schedule(new PeriodicTask(), rand.nextInt(50));
		}
		
		private class PeriodicTask extends TimerTask{
			@Override
			public void run() {
				try {
					Peer.mdb_socket.send(p);
					TaskManager task = new TaskManager();
					task.startTask(message, chunk);
					timer.cancel();
					timer.purge();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}	
		}
	}
	
	public class TaskManager {
	    private Timer timer = new Timer();
	    Message msg;
	    Chunk chunk;
	    
	    public void startTask(Message msg, Chunk chunk) {
	    	this.msg = msg;
	    	this.chunk = chunk;
	        timer.schedule(new PeriodicTask(), timeInterval);
	    }

	    private class PeriodicTask extends TimerTask {
	        @Override
	        public void run(){
	        	count++;
	        	if(count == 5){
	        		System.out.println("GAME OVER MAN, GAME OVER!");
	        		timer.cancel();
	        		timer.purge();
	        	}else{
	        		int numStored = Peer.getStoredMessages(chunk);
	        		if(numStored >= chunk.replicationDeg){
	        			System.out.println("RECEIVED CONFIRMATION OF STORED CHUNK NO " + chunk.chunkNo + " DRD: " + chunk.replicationDeg);
	        			Peer.mutex_stored_messages.lock();
	        			Peer.removeStoredMessages(chunk);
	        			Peer.mutex_stored_messages.unlock();
	        			timer.cancel();
		        		timer.purge();
	        		}else{
	        			try {
	        				System.out.println("MISSING CHUNK NO " + chunk.chunkNo);
							Peer.mdb_socket.send(msgPacket);
						} catch (IOException e) {
							e.printStackTrace();
						}
	        			timeInterval*=2;
	        			timer.schedule(new PeriodicTask(), timeInterval);
	        		}
	        	}
	        }
	    }
	}
}
