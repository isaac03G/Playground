/**
 * Capa de acceso a datos para {@link com.proyecto.aula.playgroundv2.model.Usuario}.
 * <p>
 * Operaciones CRUD heredadas de {@link org.springframework.data.mongodb.repository.MongoRepository}
 * más búsquedas por credenciales únicas (email y username) usadas en login y registro.
 * </p>
 */
package com.proyecto.aula.playgroundv2.repository;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.MongoRepository;
import com.proyecto.aula.playgroundv2.model.Usuario;

/**
 * Repositorio MongoDB de usuarios registrados.
 */
public interface UsuarioRepository extends MongoRepository< Usuario, String> {

    /**
     * Busca un usuario por correo electrónico (recuperación de contraseña, login alternativo).
     *
     * @param email dirección indexada de forma única dispersa
     * @return usuario si existe
     */
    Optional<Usuario> findByEmail(String email);

    /**
     * Busca un usuario por nombre de usuario (flujo principal de autenticación).
     *
     * @param username login único
     * @return usuario si existe
     */
    Optional<Usuario> findByUsername(String username);

    /**
     * Clientes (todo el que NO es administrador) cuyo username, email o teléfono coincide con la
     * expresión regular indicada (sin distinguir mayúsculas), paginados.
     * Los usuarios sin rol se consideran clientes. Requiere MongoDB 4.0.7+ por el {@code $not} con {@code $regex}.
     *
     * @param regex expresión regular ya escapada (use {@code .*} para no filtrar)
     */
    @Query("{ 'role': { $not: { $regex: 'ADMIN', $options: 'i' } }, $or: [ "
         + "{ 'username': { $regex: ?0, $options: 'i' } }, "
         + "{ 'email': { $regex: ?0, $options: 'i' } }, "
         + "{ 'telefono': { $regex: ?0, $options: 'i' } } ] }")
    Page<Usuario> buscarClientes(String regex, Pageable pageable);

}
