import java.lang.Boolean
import terracotta

dso = terracotta.DSO(AdminTask)
dso.enable()

s1 = AdminConfig.getid('/Server:server1/')
SM = AdminConfig.list( 'SessionManager', s1 )
smAttr = [ [ 'enableUrlRewriting', 'true' ] ]

print 'SessionManager before:'
print AdminConfig.show( SM )
AdminConfig.modify( SM, smAttr )
print 'SessionManager  after:'
print AdminConfig.show( SM )
AdminConfig.save()
        
if AdminConfig.hasChanges():
    AdminConfig.save()
