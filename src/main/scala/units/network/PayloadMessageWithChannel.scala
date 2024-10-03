package units.network

import io.netty.channel.Channel

case class PayloadMessageWithChannel(pm: PayloadMessage, ch: Channel)
