# claude-weather
* Downloads weather radar for Dublin every 15 minutes, to a file called 'latest.png',
which can them be displayed as a background on a computer screen.
* Mostly written by claude.

## Things of interest
* Claude was able to find an API key / licence key free radar web site without needing help
* When I asked for the image be cropped to 16:9 (for a screen), it stretched it instead and had to be chided.
* When I asked for the zoom to be increased it didn't notice it's changes didn't work, as the API still returned data.

## Prompts

* Please write a java application that finds and downloads a weather radar map of dublin, ireland, at 15 minute intervals                                                 
* Please change the coverage so it focuses on Dublin and Leinster 
* Am getting "Zoom level not supported" in the images     
* Please change the crop so it's 16:9  
* You are stretching the image to 16:9. I need it cropped to 16:9    
* Rename Main.java to GetRadar.java. And move it to the new package 'ie.rolfe.weatherradar'
* One last thing: Always copy the latest radar image to a file called 'latest.png'
* Will this code run on a raspberry pi?
* that's good! Please commit and push to git   

