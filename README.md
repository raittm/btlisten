# btlisten
android mp3 player over bluetooth

I haven't got a bluetooth speaker, but I have a few old phones, so for my first android app I decided to try and hack a bluetooth streaming/player with some control between the two to emulate one.

The idea is you hook a phone with the player app (btlisten) up to an amp via its headphone connection, then use the streaming app (btstream) to send files to play.

The player app is dumb, and just plays the raw mp3 file data that is streamed across.

The streaming app has a simple file manager, playlist (can add directories or individual files) and can send volume up/down.

There is some link up/down detection between the two, so that you can exit the streamer on one phone, then connect a streamer from another phone, without having to do anything with the player phone. 

The phones with btlisten and btstream MUST be paired first, then a connection is initiated from the streaming phone, from its settings menu. 


