package akka.android

import akka.actor._
import akka.cluster.Cluster
import android.app.Activity
import android.content.Context._
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.format.Formatter
import android.util.Log
import android.widget.TextView
import com.typesafe.config.ConfigFactory

trait AppInterface {
  def addEntry(text: String)
  def log(tag: String, msg: String)
}

class MainActivity extends Activity with TypedActivity with AppInterface {
  var actorSystem = Option.empty[ActorSystem]

  override def onCreate(savedInstanceState: Bundle) = {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.mainlayout)

    val ipAddress = {
      val connInfo = getSystemService(WIFI_SERVICE).asInstanceOf[WifiManager].getConnectionInfo
      Formatter.formatIpAddress(connInfo.getIpAddress)
    }

    val customConf = ConfigFactory.parseString(s"akka.remote.netty.tcp.hostname = $ipAddress")
    val config = customConf.withFallback(ConfigFactory.load)

    actorSystem = Some(ActorSystem("AndroidCluster", config))
    actorSystem.map(_.actorOf(Props(new ClusterListenerActor(this))))
  }

  private def onUI(block: => Unit) = runOnUiThread(new Runnable { def run() = block })

  def addEntry(text: String) = onUI {
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

object MainApp extends App with AppInterface {
  val system = ActorSystem("AndroidCluster")
  system.actorOf(Props(new ClusterListenerActor(this)))

  def addEntry(text: String) = println("ENTRY: " + text)
  def log(tag: String, msg: String) = println(tag + " " + msg)
}
