object Main extends App {
  println("Hello")
}

package test {

  import javax.servlet.http._

  class MyServlet extends HttpServlet {
    val html = <html>
    <head><title>MyServlet</title></head>
    <body>Hello</body>
    </html>

    override def doGet(request: HttpServletRequest, response: HttpServletResponse) {
      response.setContentType("text/html")
      response.getWriter().print(html.toString)
    }
  }
}
