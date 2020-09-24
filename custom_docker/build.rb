DOCKER_IMAGE = "callumjones/traccar:"

version = File.read("../VERSION").strip

final_image = DOCKER_IMAGE + version

puts %x[docker build -t #{final_image} .]
puts %x[docker push #{final_image}]