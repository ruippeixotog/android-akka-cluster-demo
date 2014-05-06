package akka.android

import akka.actor._
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import akka.io.{IO, Udp}
import java.net.InetSocketAddress
import akka.util.ByteString
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import android.util.Log
import android.net.{NetworkInfo, ConnectivityManager}
import android.content.Context

case object PongTimeout

class ClusterListenerActor(activity: Act) extends Actor {
  final val TAG = "ClusterListenerActor"

  implicit def system = context.system
  implicit def executionContext = context.system.dispatcher
  private def onUI(block: => Unit) = activity.runOnUiThread(new Runnable { def run() = block })

  activity.log(TAG, "Trying to bind to UDP port 2551")
  IO(Udp) ! Udp.Bind(self, new InetSocketAddress(2551), List(Udp.SO.Broadcast(true)))

  val cluster = Cluster(context.system)
  activity.log(TAG, "Cluster self address is " + cluster.selfAddress)

  def receive = {
    case Udp.Bound(localAddr) =>
      activity.log(TAG, "Bound to UDP address " + localAddr)
      sender() ! Udp.Send(ByteString("ping"), new InetSocketAddress("255.255.255.255", 2551))
      context.system.scheduler.scheduleOnce(15 seconds, self, PongTimeout)
      context.become(waitingForPong(sender()))
  }

  def waitingForPong(udp: ActorRef): Receive = {

    case Udp.Received(data, udpSender) if new String(data.toArray) == "pong" =>
      activity.log(TAG, "Found first host " + udpSender.getHostName)

      val addr = Address("akka.tcp", "AndroidCluster", udpSender.getHostName, 2550)
      activity.log(TAG, "Host cluster address is " + addr)

      cluster.join(addr)
      cluster.subscribe(self, classOf[ClusterDomainEvent])
      context.become(joinedCluster(udp))

    case PongTimeout =>
      activity.log(TAG, "No hosts found")
      cluster.join(cluster.selfAddress)
      cluster.subscribe(self, classOf[ClusterDomainEvent])
      context.become(joinedCluster(udp))
  }

  def joinedCluster(udp: ActorRef): Receive = {
    case Udp.Received(data, udpSender) if new String(data.toArray) == "ping" =>
      activity.log(TAG, "Received ping from " + udpSender.getHostName)
      udp ! Udp.Send(ByteString("pong"), udpSender)

    case ccs: CurrentClusterState =>
      onUI { activity.addEntry("Current cluster members: " + ccs.members.mkString(", ")) }

    case MemberUp(member) => onUI { activity.addEntry("Member up: " + member) }
    case MemberRemoved(member, _) => onUI { activity.addEntry("Member down: " + member) }
  }
}

trait Act {
  def addEntry(text: String)
  def runOnUiThread(runnable: Runnable)
  def log(tag: String, msg: String)
}

class MainActivity extends Activity with TypedActivity with Act {
  var actorSystem = Option.empty[ActorSystem]

  override def onCreate(savedInstanceState: Bundle) = {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.mainlayout)

    actorSystem = Some(ActorSystem("AndroidCluster"))

    actorSystem.map(_.actorOf(Props(new ClusterListenerActor(this))))
  }

  def addEntry(text: String) {
    val tv = new TextView(this)
    tv.setText(text)
    findView(TR.log_view).addView(tv)
  }

  def log(tag: String, msg: String) = Log.e(tag, msg)

  override def onDestroy() = {
    actorSystem.foreach { system =>
      val cluster = Cluster(system)
      cluster.leave(cluster.selfAddress)
      system.shutdown()
    }
    super.onDestroy()
  }
}

object MainApp extends App with Act {
  var actorSystem = Option.empty[ActorSystem]

  actorSystem = Some(ActorSystem("AndroidCluster"))

  actorSystem.map(_.actorOf(Props(new ClusterListenerActor(this))))

  def addEntry(text: String) = println("ENTRY: " + text)
  def runOnUiThread(runnable: Runnable) = runnable.run()
  def log(tag: String, msg: String) = println(tag + " " + msg)
}
